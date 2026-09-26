package octometer.monitor

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.nio.charset.StandardCharsets
import java.util.Collections
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory

/** The poll bound of [awaitCallLoggingLine]: two seconds. */
private const val AWAIT_LINE_BOUND_MILLIS = 2_000L
private const val AWAIT_LINE_STEP_MILLIS = 20L

/**
 * The TRACE search of issue #31, correction 3. Read the rule in
 * `brief-internal-api.md`: a new Ktor plugin needs the same TRACE
 * search on a real Netty server, for a 200, a 400, a 404, and a 415,
 * with a marker in the query string.
 *
 * This class also proves the positive rule of step 3 (correction round
 * 1, MAJOR 1 of the Kotlin and Ktor review of pull request #211): each
 * case asserts the one exact log line of the `CallLogging` plugin, on
 * the logger `io.ktor.server.Application`. A negative check alone
 * cannot catch a plugin that writes no line at all, or a wrong format.
 * A fifth case covers the 403 of `RequestGuard`'s Host check, so the
 * proof also covers a rejection before a route runs.
 *
 * Correction round 2 (a new observation of the Kotlin and Ktor review):
 * the plugin writes its line from the Netty event loop, on a thread
 * other than the one that reads the raw socket response. A read of the
 * capture list right after that read can then race the write, most of
 * all in the 403 case, where no route runs and the plugin's line is the
 * only event. [awaitCallLoggingLine] polls the capture list for the
 * expected line, with a bound of two seconds, before the capture block
 * ends. It runs inside the block, so its wait sits inside the same
 * window that [captureLogEvents] raises the root level for.
 *
 * [withRawSocketServer] starts a real Netty engine, not the test engine
 * of `testApplication` (issue #148 lesson, brief-internal-api.md).
 */
class CallLoggingTraceTest {

    @Test
    fun `a 200 from health writes the one line METHOD PATH, with no marker at any level`() {
        val marker = "callmarker-200-a1c3"
        val expectedLine = "GET /api/health"
        withRawSocketServer { port, send ->
            val (response, events) = runBlocking {
                captureLogEvents {
                    val result = send(
                        "GET /api/health?marker=$marker HTTP/1.1\r\n" +
                            "Host: localhost:$port\r\n" +
                            "Connection: close\r\n" +
                            "\r\n",
                    )
                    awaitCallLoggingLine(expectedLine)
                    result
                }
            }

            assertTrue(response.startsWith("HTTP/1.1 200"), "expected 200, first line of:\n$response")
            assertNoLineHoldsMarker(events, marker)
            assertOneCallLoggingLine(events, expectedLine)
        }
    }

    @Test
    fun `a 400 from a bad page parameter writes the one line METHOD PATH, with no marker at any level`() {
        val marker = "callmarker-400-d4f6"
        val expectedLine = "GET /api/apps/1/users"
        withRawSocketServer { port, send ->
            val (response, events) = runBlocking {
                captureLogEvents {
                    val result = send(
                        "GET /api/apps/1/users?page=not-a-number&marker=$marker HTTP/1.1\r\n" +
                            "Host: localhost:$port\r\n" +
                            "Connection: close\r\n" +
                            "\r\n",
                    )
                    awaitCallLoggingLine(expectedLine)
                    result
                }
            }

            assertTrue(response.startsWith("HTTP/1.1 400"), "expected 400, first line of:\n$response")
            assertNoLineHoldsMarker(events, marker)
            assertOneCallLoggingLine(events, expectedLine)
        }
    }

    @Test
    fun `a 404 from an unmatched route writes the one line METHOD PATH, with no marker at any level`() {
        val marker = "callmarker-404-g7i9"
        val expectedLine = "GET /api/no-such-route"
        withRawSocketServer { port, send ->
            val (response, events) = runBlocking {
                captureLogEvents {
                    val result = send(
                        "GET /api/no-such-route?marker=$marker HTTP/1.1\r\n" +
                            "Host: localhost:$port\r\n" +
                            "Connection: close\r\n" +
                            "\r\n",
                    )
                    awaitCallLoggingLine(expectedLine)
                    result
                }
            }

            assertTrue(response.startsWith("HTTP/1.1 404"), "expected 404, first line of:\n$response")
            assertNoLineHoldsMarker(events, marker)
            assertOneCallLoggingLine(events, expectedLine)
        }
    }

    @Test
    fun `a 415 from an unsupported content type writes the one line METHOD PATH, with no marker at any level`() {
        val marker = "callmarker-415-j1l2"
        val expectedLine = "POST /api/health"
        withRawSocketServer { port, send ->
            val body = "x"
            val (response, events) = runBlocking {
                captureLogEvents {
                    val result = send(
                        "POST /api/health?marker=$marker HTTP/1.1\r\n" +
                            "Host: localhost:$port\r\n" +
                            "Origin: http://localhost:$port\r\n" +
                            "Content-Type: text/plain\r\n" +
                            "Content-Length: ${body.toByteArray(StandardCharsets.US_ASCII).size}\r\n" +
                            "Connection: close\r\n" +
                            "\r\n" +
                            body,
                    )
                    awaitCallLoggingLine(expectedLine)
                    result
                }
            }

            assertTrue(response.startsWith("HTTP/1.1 415"), "expected 415, first line of:\n$response")
            assertNoLineHoldsMarker(events, marker)
            assertOneCallLoggingLine(events, expectedLine)
        }
    }

    // MAJOR 1 of the Kotlin and Ktor review of pull request #211: the
    // plugin sits before RequestGuard in the pipeline (Application.kt),
    // so a 403 of the Host check must still write its own CallLogging
    // line. A wrong Host header never reaches a route handler.
    @Test
    fun `a 403 from a wrong Host header writes the one line METHOD PATH, with no marker at any level`() {
        val marker = "callmarker-403-m3n5"
        val expectedLine = "GET /api/health"
        withRawSocketServer { port, send ->
            val (response, events) = runBlocking {
                captureLogEvents {
                    val result = send(
                        "GET /api/health?marker=$marker HTTP/1.1\r\n" +
                            "Host: evil.example\r\n" +
                            "Connection: close\r\n" +
                            "\r\n",
                    )
                    awaitCallLoggingLine(expectedLine)
                    result
                }
            }

            assertTrue(response.startsWith("HTTP/1.1 403"), "expected 403, first line of:\n$response")
            assertNoLineHoldsMarker(events, marker)
            assertOneCallLoggingLine(events, expectedLine)
        }
    }

    private fun assertNoLineHoldsMarker(events: List<ILoggingEvent>, marker: String) {
        assertTrue(events.isNotEmpty(), "the capture read no log event")
        for (event in events) {
            assertFalse(event.formattedMessage.contains(marker), "a log line held the marker: ${event.formattedMessage}")
            var throwableProxy = event.throwableProxy
            while (throwableProxy != null) {
                assertFalse(
                    throwableProxy.message?.contains(marker) == true,
                    "an exception message held the marker: ${throwableProxy.message}",
                )
                throwableProxy = throwableProxy.cause
            }
        }
    }

    /**
     * The positive rule of MAJOR 1: exactly one event of the logger
     * `io.ktor.server.Application` must hold the exact text
     * [expectedMessage]. A plugin that writes no line, or a format
     * that adds the query string or a header, fails this assertion.
     */
    private fun assertOneCallLoggingLine(events: List<ILoggingEvent>, expectedMessage: String) {
        val matching = events.filter { it.loggerName == "io.ktor.server.Application" && it.formattedMessage == expectedMessage }
        assertEquals(
            1,
            matching.size,
            "expected one line \"$expectedMessage\" on io.ktor.server.Application, got:\n" +
                events.joinToString("\n") { "${it.loggerName} - ${it.formattedMessage}" },
        )
    }

    /**
     * Correction round 2: the `CallLogging` plugin writes its line from
     * the Netty event loop, a thread other than the one that reads the
     * raw socket response inside [withRawSocketServer]. This function
     * attaches its own, short-lived appender to the root logger, and it
     * polls that appender's list for [expectedMessage], with a bound of
     * [AWAIT_LINE_BOUND_MILLIS]. It runs inside the same
     * [captureLogEvents] block that [assertOneCallLoggingLine] later
     * reads, so a poll that finds the line here guarantees that the
     * outer capture holds it too, once the block returns.
     *
     * This function never fails the test on a timeout. A missing line
     * still reaches [assertOneCallLoggingLine], with its own clear
     * failure message.
     */
    private suspend fun awaitCallLoggingLine(expectedMessage: String) {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>()
        val list = Collections.synchronizedList(ArrayList<ILoggingEvent>())
        appender.list = list
        appender.start()
        root.addAppender(appender)
        try {
            val deadline = System.nanoTime() + AWAIT_LINE_BOUND_MILLIS * 1_000_000
            while (System.nanoTime() < deadline) {
                val found = synchronized(list) { list.any { it.formattedMessage == expectedMessage } }
                if (found) return
                delay(AWAIT_LINE_STEP_MILLIS)
            }
        } finally {
            root.detachAppender(appender)
            appender.stop()
        }
    }
}
