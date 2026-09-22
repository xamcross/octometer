package octometer.monitor

import ch.qos.logback.classic.Level
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import octometer.monitor.config.MonitorConfig
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Issue #148: a bad percent escape in a query string must give 400, not
// 500. The Ktor test client rewrites a bad escape before the request
// leaves the client (see RequestGuardRawSocketTest), so this test uses a
// real socket on a real server, the same pattern.
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

            assertTrue(response.startsWith("HTTP/1.1 400"), "expected 400, first line of:\n$response")
            assertTrue(
                response.contains("{\"error\":\"The request is not valid.\"}"),
                "expected the fixed error body in:\n$response",
            )
            assertFalse(response.contains(BAD_ESCAPE), "the response must not echo the query string:\n$response")
            assertFalse(
                events.any { it.level == Level.ERROR },
                "expected no ERROR log line, got:\n${events.joinToString("\n") { it.formattedMessage }}",
            )
            assertFalse(
                events.any { it.formattedMessage.contains(BAD_ESCAPE) },
                "no log line at any level may hold the query string, got:\n" +
                    events.joinToString("\n") { it.formattedMessage },
            )
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

            assertTrue(response.startsWith("HTTP/1.1 400"), "expected 400, first line of:\n$response")
            assertTrue(
                response.contains("{\"error\":\"The request is not valid.\"}"),
                "expected the fixed error body in:\n$response",
            )
            assertFalse(response.contains(BAD_ESCAPE), "the response must not echo the query string:\n$response")
            assertFalse(
                events.any { it.level == Level.ERROR },
                "expected no ERROR log line, got:\n${events.joinToString("\n") { it.formattedMessage }}",
            )
            assertFalse(
                events.any { it.formattedMessage.contains(BAD_ESCAPE) },
                "no log line at any level may hold the query string, got:\n" +
                    events.joinToString("\n") { it.formattedMessage },
            )
        }
    }

    // A throw of the app code itself, with a valid query string, must
    // still give the fixed 500 body. The catch-all handler must stay.
    @Test
    fun `a plain app failure still gives the fixed 500 body`() {
        withRawSocketServer { port, send ->
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

// Finds a free loopback port, starts a real server on it with a probe
// route installed, hands the caller one function to send a raw request
// and read the raw response, then always stops the server. The pattern
// of RequestGuardRawSocketTest, so the query-string test can send a bad
// percent escape the Ktor test client would otherwise rewrite.
private fun withRawSocketServer(block: (port: Int, send: (String) -> String) -> Unit) {
    val port = ServerSocket(0).use { it.localPort }
    val config = MonitorConfig(
        mode = "prod",
        port = port,
        dataDir = testDataDir(),
        settleLagSeconds = 60,
        retentionDays = 395,
    )
    val server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
        module(config)
        routing {
            get("/api/probe/failure-raw") {
                throw IllegalStateException("fake-failure-raw-9c31a0")
            }
        }
    }
    server.start(wait = false)
    try {
        block(port) { rawRequest -> sendRawRequest(port, rawRequest) }
    } finally {
        server.stop(gracePeriodMillis = 0, timeoutMillis = 1000)
    }
}

private fun sendRawRequest(port: Int, rawRequest: String): String {
    Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 5000
        socket.getOutputStream().write(rawRequest.toByteArray(StandardCharsets.US_ASCII))
        socket.getOutputStream().flush()
        return socket.getInputStream().readBytes().toString(StandardCharsets.US_ASCII)
    }
}
