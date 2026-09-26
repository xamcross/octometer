package octometer.monitor

import ch.qos.logback.classic.spi.ILoggingEvent
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The TRACE search of issue #31, correction 3: "a new Ktor plugin needs
 * the same TRACE search on a real Netty server (a 200, a 400, a 404, a
 * 415, a marker in the query string) before its first use." This class
 * ran with no `CallLogging` plugin installed, to confirm the baseline of
 * "Application.kt" before the change (a run with the plugin absent). It
 * keeps running after the plugin lands, so a later change to the format
 * of D11 and D15 still catches a leak.
 *
 * [withRawSocketServer] starts a real Netty engine, not the test engine
 * of `testApplication` (issue #148 lesson, brief-internal-api.md). A
 * marker in the query string of each request must reach no log line, at
 * any level, once [captureLogEvents] raises the root level to TRACE.
 */
class CallLoggingTraceTest {

    @Test
    fun `a 200 from health writes no line with the marker, at any level`() {
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
        }
    }

    @Test
    fun `a 400 from a bad page parameter writes no line with the marker, at any level`() {
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
        }
    }

    @Test
    fun `a 404 from an unmatched route writes no line with the marker, at any level`() {
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
        }
    }

    @Test
    fun `a 415 from an unsupported content type writes no line with the marker, at any level`() {
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
}
