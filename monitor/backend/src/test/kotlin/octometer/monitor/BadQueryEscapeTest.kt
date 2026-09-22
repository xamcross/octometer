package octometer.monitor

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Issue #148: a bad percent escape must give 400, not 500, in the path
// and in the query string.
// The Ktor test client rewrites a bad escape before the request leaves
// the client. See RequestGuardRawSocketTest. This test uses a real
// socket on a real server, the same pattern.
private const val BAD_ESCAPE = "%zz"

class BadQueryEscapeTest {

    @Test
    fun `a bad percent escape in the query string of health gives 400, with the fixed body and no ERROR line`() {
        withRawSocketServer { port, send ->
            val (response, events) = runBlocking {
                captureLogEvents {
                    send(
                        "GET /api/health?x=$BAD_ESCAPE HTTP/1.1\r\n" +
                            "Host: localhost:$port\r\n" +
                            "Connection: close\r\n" +
                            "\r\n",
                    )
                }
            }

            assertFixedBadRequest(response, events)
        }
    }

    // Step 5 of issue #148: a route beyond health with a query parameter
    // gets the same fix, because RequestGuard checks only the path, not
    // the query string.
    @Test
    fun `a bad percent escape in the query string of a users route gives 400 too`() {
        withRawSocketServer { port, send ->
            val (response, events) = runBlocking {
                captureLogEvents {
                    send(
                        "GET /api/apps/1/users?q=$BAD_ESCAPE HTTP/1.1\r\n" +
                            "Host: localhost:$port\r\n" +
                            "Connection: close\r\n" +
                            "\r\n",
                    )
                }
            }

            assertFixedBadRequest(response, events)
        }
    }

    // Correction round 1, MAJOR 1: a bad escape in the path gives a Ktor
    // message that names the raw request target, not the fixed text of a
    // query failure. This case proves that the handler never logs that
    // message.
    @Test
    fun `a bad percent escape in the path of a users route gives 400 too, with no leak of the escape`() {
        withRawSocketServer { port, send ->
            val (response, events) = runBlocking {
                captureLogEvents {
                    send(
                        "GET /api/apps/1/users/$BAD_ESCAPE HTTP/1.1\r\n" +
                            "Host: localhost:$port\r\n" +
                            "Connection: close\r\n" +
                            "\r\n",
                    )
                }
            }

            assertFixedBadRequest(response, events)
        }
    }

    // A throw of the app code itself, with a valid query string, must
    // still give the fixed 500 body. The catch-all handler must stay.
    @Test
    fun `a plain app failure still gives the fixed 500 body`() {
        withRawSocketServer(routes = { probeFailureRoute() }) { port, send ->
            val response = send(
                "GET /api/probe/failure-raw HTTP/1.1\r\n" +
                    "Host: localhost:$port\r\n" +
                    "Connection: close\r\n" +
                    "\r\n",
            )

            assertTrue(response.startsWith("HTTP/1.1 500"), "expected 500, first line of:\n$response")
            assertTrue(
                response.contains("{\"error\":\"The server had an internal error.\"}"),
                "expected the fixed 500 body in:\n$response",
            )
        }
    }
}

// Correction round 1, MINOR 4: the five assertions of a fixed 400 answer,
// in one place. Each case above then holds the request and one call.
private fun assertFixedBadRequest(response: String, events: List<ILoggingEvent>) {
    assertTrue(response.startsWith("HTTP/1.1 400"), "expected 400, first line of:\n$response")
    assertTrue(
        response.contains("{\"error\":\"The request is not valid.\"}"),
        "expected the fixed error body in:\n$response",
    )
    assertFalse(response.contains(BAD_ESCAPE), "the response must not echo the escape:\n$response")
    assertFalse(
        events.any { it.level == Level.ERROR },
        "expected no ERROR log line, got:\n${events.joinToString("\n") { it.formattedMessage }}",
    )
    assertFalse(
        events.any { it.formattedMessage.contains(BAD_ESCAPE) },
        "no log line at any level may hold the escape, got:\n" +
            events.joinToString("\n") { it.formattedMessage },
    )
}

private fun Route.probeFailureRoute() {
    get("/api/probe/failure-raw") {
        throw IllegalStateException("fake-failure-raw-9c31a0")
    }
}
