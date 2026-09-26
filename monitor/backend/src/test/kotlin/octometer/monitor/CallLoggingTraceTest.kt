package octometer.monitor

import ch.qos.logback.classic.spi.ILoggingEvent
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
 * [withRawSocketServer] starts a real Netty engine, not the test engine
 * of `testApplication` (issue #148 lesson, brief-internal-api.md).
 */
class CallLoggingTraceTest {

    @Test
    fun `a 200 from health writes the one line METHOD PATH, with no marker at any level`() {
        val marker = "callmarker-200-a1c3"
        withRawSocketServer { port, send ->
            val (response, events) = runBlocking {
                captureLogEvents {
                    send(
                        "GET /api/health?marker=$marker HTTP/1.1\r\n" +
                            "Host: localhost:$port\r\n" +
                            "Connection: close\r\n" +
                            "\r\n",
                    )
                }
            }

            assertTrue(response.startsWith("HTTP/1.1 200"), "expected 200, first line of:\n$response")
            assertNoLineHoldsMarker(events, marker)
            assertOneCallLoggingLine(events, "GET /api/health")
        }
    }

    @Test
    fun `a 400 from a bad page parameter writes the one line METHOD PATH, with no marker at any level`() {
        val marker = "callmarker-400-d4f6"
        withRawSocketServer { port, send ->
            val (response, events) = runBlocking {
                captureLogEvents {
                    send(
                        "GET /api/apps/1/users?page=not-a-number&marker=$marker HTTP/1.1\r\n" +
                            "Host: localhost:$port\r\n" +
                            "Connection: close\r\n" +
                            "\r\n",
                    )
                }
            }

            assertTrue(response.startsWith("HTTP/1.1 400"), "expected 400, first line of:\n$response")
            assertNoLineHoldsMarker(events, marker)
            assertOneCallLoggingLine(events, "GET /api/apps/1/users")
        }
    }

    @Test
    fun `a 404 from an unmatched route writes the one line METHOD PATH, with no marker at any level`() {
        val marker = "callmarker-404-g7i9"
        withRawSocketServer { port, send ->
            val (response, events) = runBlocking {
                captureLogEvents {
                    send(
                        "GET /api/no-such-route?marker=$marker HTTP/1.1\r\n" +
                            "Host: localhost:$port\r\n" +
                            "Connection: close\r\n" +
                            "\r\n",
                    )
                }
            }

            assertTrue(response.startsWith("HTTP/1.1 404"), "expected 404, first line of:\n$response")
            assertNoLineHoldsMarker(events, marker)
            assertOneCallLoggingLine(events, "GET /api/no-such-route")
        }
    }

    @Test
    fun `a 415 from an unsupported content type writes the one line METHOD PATH, with no marker at any level`() {
        val marker = "callmarker-415-j1l2"
        withRawSocketServer { port, send ->
            val body = "x"
            val (response, events) = runBlocking {
                captureLogEvents {
                    send(
                        "POST /api/health?marker=$marker HTTP/1.1\r\n" +
                            "Host: localhost:$port\r\n" +
                            "Origin: http://localhost:$port\r\n" +
                            "Content-Type: text/plain\r\n" +
                            "Content-Length: ${body.toByteArray(StandardCharsets.US_ASCII).size}\r\n" +
                            "Connection: close\r\n" +
                            "\r\n" +
                            body,
                    )
                }
            }

            assertTrue(response.startsWith("HTTP/1.1 415"), "expected 415, first line of:\n$response")
            assertNoLineHoldsMarker(events, marker)
            assertOneCallLoggingLine(events, "POST /api/health")
        }
    }

    // MAJOR 1 of the Kotlin and Ktor review of pull request #211: the
    // plugin sits before RequestGuard in the pipeline (Application.kt),
    // so a 403 of the Host check must still write its own CallLogging
    // line. A wrong Host header never reaches a route handler.
    @Test
    fun `a 403 from a wrong Host header writes the one line METHOD PATH, with no marker at any level`() {
        val marker = "callmarker-403-m3n5"
        withRawSocketServer { port, send ->
            val (response, events) = runBlocking {
                captureLogEvents {
                    send(
                        "GET /api/health?marker=$marker HTTP/1.1\r\n" +
                            "Host: evil.example\r\n" +
                            "Connection: close\r\n" +
                            "\r\n",
                    )
                }
            }

            assertTrue(response.startsWith("HTTP/1.1 403"), "expected 403, first line of:\n$response")
            assertNoLineHoldsMarker(events, marker)
            assertOneCallLoggingLine(events, "GET /api/health")
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
}
