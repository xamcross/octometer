package octometer.monitor.frontend

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import octometer.monitor.module
import octometer.monitor.prodConfig
import octometer.monitor.registerTempRoot
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The traversal guard of `resolveRequestedFile` in StaticFrontend.kt,
 * correction round 1 of issue #38 (BLOCKER 2, security review; MAJOR 3,
 * release review). The Ktor test client normalises a raw target such as
 * "/%2e%2e/" before it leaves the client, the same limit that
 * RequestGuardRawSocketTest names, so each test here sends a raw socket
 * request instead.
 *
 * I removed the `isInside` check of `resolveRequestedFile` for a moment
 * (`realCandidate.takeIf { Files.isRegularFile(it) }`, with no root
 * check), ran the first test of this file, and saw it fail: the answer
 * for "/../marker.txt" held the marker body. I restored the check
 * before this run.
 */
class StaticFrontendTraversalTest {

    private val indexBody = "<html><body>octometer</body></html>"
    private val markerBody = "MARKER-OUTSIDE-STATIC"

    @Test
    fun `each traversal form gets index html, 404, or 400, never the marker file outside the static folder`() {
        val staticDir = staticDirWithMarkerOutside()
        File(staticDir, "sub").mkdirs()

        withServerOn(staticDir) { port, send ->
            val targets = listOf(
                "/../marker.txt",
                "/%2e%2e/marker.txt",
                "/..%2fmarker.txt",
                "/..%5cmarker.txt",
                "/..\\marker.txt",
                "/../marker.txt%00.js",
                "/a/../../marker.txt",
                "/%2e%2e%2f%2e%2e%2fmarker.txt",
                "/" + "a".repeat(5000) + "/../marker.txt",
                "/sub",
            )
            for (target in targets) {
                val response = send(
                    "GET $target HTTP/1.1\r\n" +
                        "Host: localhost:$port\r\n" +
                        "Connection: close\r\n" +
                        "\r\n",
                )
                assertFalse(response.contains(markerBody), "target $target leaked the marker:\n$response")
                // A target of 5000 "a" characters gives a Netty 400 on
                // the request line itself, over HTTP/1.0, before the
                // routing of this module ever runs (a safe answer, and
                // not one this test can shape).
                val statusLine = response.substringBefore("\r\n")
                val safeAnswer = response.contains(indexBody) ||
                    statusLine.contains(" 404 ") ||
                    statusLine.contains(" 400 ")
                assertTrue(safeAnswer, "target $target gave neither index.html nor 404/400:\n$response")
            }
        }
    }

    @Test
    fun `a junction inside the static folder cannot serve a file outside it`() {
        assumeTrue(isWindows(), "a junction is a Windows reparse point")
        val installRoot = Files.createTempDirectory("octometer-junction-test-").toFile().also { registerTempRoot(it) }
        val staticDir = File(installRoot, "static").also { it.mkdirs() }
        File(staticDir, "index.html").writeText(indexBody)
        val secretsDir = File(installRoot, "secrets").also { it.mkdirs() }
        File(secretsDir, "apps.json").writeText(markerBody)
        val linkPath = File(staticDir, "link")
        val junctionCreated = createWindowsJunction(linkPath, secretsDir)
        assumeTrue(junctionCreated, "could not create a junction on this machine")

        withServerOn(staticDir) { port, send ->
            val response = send(
                "GET /link/apps.json HTTP/1.1\r\n" +
                    "Host: localhost:$port\r\n" +
                    "Connection: close\r\n" +
                    "\r\n",
            )

            assertFalse(response.contains(markerBody), "the junction leaked the marker:\n$response")
        }
    }

    private fun staticDirWithMarkerOutside(): File {
        val installRoot = Files.createTempDirectory("octometer-traversal-test-").toFile().also { registerTempRoot(it) }
        val staticDir = File(installRoot, "static").also { it.mkdirs() }
        File(staticDir, "index.html").writeText(indexBody)
        File(installRoot, "marker.txt").writeText(markerBody)
        return staticDir
    }
}

private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("windows")

// "mklink /J" needs no administrator right on Windows, unlike a symbolic
// link. It gives false when the command fails, for example on a machine
// with the feature turned off.
private fun createWindowsJunction(link: File, target: File): Boolean {
    val process = ProcessBuilder("cmd", "/c", "mklink", "/J", link.absolutePath, target.absolutePath)
        .redirectErrorStream(true)
        .start()
    process.waitFor()
    return process.exitValue() == 0 && link.exists()
}

// Starts a real server on a free loopback port, with staticDir wired in,
// hands the caller one function to send a raw request and read the raw
// response, then always stops the server. The Ktor test client cannot
// send a malformed or a pre-encoded target as-is (RequestGuardRawSocketTest
// names the same limit), so this helper mirrors it for the static route.
private fun withServerOn(staticDir: File, block: (port: Int, send: (String) -> String) -> Unit) {
    val port = ServerSocket(0).use { it.localPort }
    val config = prodConfig().copy(port = port)
    val server = embeddedServer(Netty, host = "127.0.0.1", port = port) { module(config, staticDir) }
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
        socket.getOutputStream().write(rawRequest.toByteArray(StandardCharsets.ISO_8859_1))
        socket.getOutputStream().flush()
        return socket.getInputStream().readBytes().toString(StandardCharsets.ISO_8859_1)
    }
}
