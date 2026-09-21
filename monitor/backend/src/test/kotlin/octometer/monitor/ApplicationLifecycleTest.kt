package octometer.monitor

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
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

private const val WRITER_THREAD_NAME = "octometer-sqlite-writer"

/**
 * Step 6 of issue #15: the application opens one SqliteDatabase in
 * dataDir at the start, and it closes it at the stop. A real server
 * starts and stops two times against one temporary folder.
 *
 * BLOCKER 2 of the Ktor review: a 200 from /api/health proves only the
 * open call, because SQLite in WAL mode admits a second reader-writer
 * pair on the same file even while the first stays open. This asserts
 * the count of threads named [WRITER_THREAD_NAME] instead, because
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

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun writerThreadCount(): Int = Thread.getAllStackTraces().keys.count { it.name == WRITER_THREAD_NAME }
}
