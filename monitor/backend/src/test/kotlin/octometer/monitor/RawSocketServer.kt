package octometer.monitor

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

// Correction round 1 of issue #148: RequestGuardRawSocketTest and
// BadQueryEscapeTest each held a private copy of this helper. This file
// gives both one shared copy.

/**
 * Finds a free loopback port and starts a real server on it. It hands
 * the caller one function: send a raw request, and read the raw
 * response. The server always stops, so a test leaves no background
 * work.
 *
 * A raw socket sends three cases the Ktor test client cannot send: a
 * repeated slash, a bad percent escape, and a malformed Content-Type
 * value. See RequestGuardRawSocketTest and BadQueryEscapeTest.
 *
 * [routes] adds one extra route for one test, for example a probe route
 * that throws. The default adds no route.
 */
fun withRawSocketServer(
    routes: Route.() -> Unit = {},
    block: (port: Int, send: (String) -> String) -> Unit,
) {
    val port = ServerSocket(0).use { it.localPort }
    val config = prodConfig(dataDir = testDataDir()).copy(port = port)
    val server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
        module(config)
        routing(routes)
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
