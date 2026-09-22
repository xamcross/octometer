package octometer.monitor

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import octometer.monitor.config.MonitorConfig
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val WRITER_THREAD_NAME = "octometer-sqlite-writer"

/**
 * Step 6 of issue #15: the application opens one SqliteDatabase in
 * dataDir at the start, and it closes it at the stop. A real server
 * starts and stops two times against one temporary folder.
 *
 * BLOCKER 2 of the Ktor review: a 200 from /api/health proves only the
 * open call. SQLite in WAL mode admits a second reader-writer pair on
 * the same file even while the first stays open. This asserts the count
 * of threads named [WRITER_THREAD_NAME] instead, because
 * SqliteDatabase.close() closes the single-thread dispatcher of the
 * writer, which ends that thread.
 */
class ApplicationLifecycleTest {

    private val dataDir = Files.createTempDirectory("octometer-lifecycle-test-").toFile().also { registerTempRoot(it) }

    @AfterTest
    fun tearDown() {
        dataDir.deleteRecursively()
    }

    @Test
    fun `the server opens and closes the store on each start and stop, twice in one folder`() {
        val client = HttpClient.newHttpClient()
        val before = writerThreadCount()

        repeat(2) {
            val port = freePort()
            val config = MonitorConfig(
                mode = "prod",
                port = port,
                dataDir = dataDir.absolutePath,
                settleLagSeconds = 60,
                retentionDays = 395,
            )
            val server = embeddedServer(Netty, host = "127.0.0.1", port = port) { module(config) }
            server.start(wait = false)
            try {
                val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/health")).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())

                assertEquals(200, response.statusCode())
                assertEquals(before + 1, writerThreadCount(), "the store must open one writer thread at the start")
            } finally {
                server.stop(gracePeriodMillis = 0, timeoutMillis = 1000)
            }
            assertEquals(before, writerThreadCount(), "the store must close its writer thread at the stop")
        }
    }

    // MAJOR 5 (second Ktor review) and MAJOR 2 (second security review).
    // A broken secrets/apps.json must not keep the writer thread alive
    // forever. The health route must still answer while the sweep is
    // skipped. This test uses its own root, so its "secrets" folder never
    // sits at the system temp root of [dataDir] above.
    @Test
    fun `a broken secrets file at the start does not keep the writer thread alive, and the health route still answers`() {
        val root = Files.createTempDirectory("octometer-lifecycle-broken-secrets-").toFile().also { registerTempRoot(it) }
        val ownDataDir = File(root, "data").absolutePath
        val secretsDir = File(root, "secrets").apply { mkdirs() }
        val secretsFile = File(secretsDir, "apps.json")
        val brokenBytes = "{ this is not valid json".toByteArray(Charsets.UTF_8)
        secretsFile.writeBytes(brokenBytes)
        val before = writerThreadCount()

        val port = freePort()
        val config = MonitorConfig(
            mode = "prod",
            port = port,
            dataDir = ownDataDir,
            settleLagSeconds = 60,
            retentionDays = 395,
        )
        val server = embeddedServer(Netty, host = "127.0.0.1", port = port) { module(config) }
        server.start(wait = false)
        try {
            val client = HttpClient.newHttpClient()
            val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/health")).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())

            assertEquals(200, response.statusCode())
            assertEquals(before + 1, writerThreadCount(), "the database must still open, although the sweep failed")
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 1000)
        }
        assertEquals(before, writerThreadCount(), "the writer thread must not survive the stop")
        assertTrue(
            brokenBytes.contentEquals(secretsFile.readBytes()),
            "the broken file must stay exactly as it was",
        )
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun writerThreadCount(): Int = Thread.getAllStackTraces().keys.count { it.name == WRITER_THREAD_NAME }
}
