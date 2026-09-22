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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import octometer.monitor.config.MonitorConfig
import octometer.monitor.store.SqliteDatabase
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

    // SQLite MAJOR 1 of correction round 1: dataDir sits under root, a
    // nested folder, so the sibling backups folder of issue #55 stays
    // inside root and never lands at the system temp root.
    private val root = Files.createTempDirectory("octometer-lifecycle-test-").toFile().also { registerTempRoot(it) }
    private val dataDir = File(root, "data")

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
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
                pollIntervalSeconds = 60,
            )
            val server = embeddedServer(Netty, host = "127.0.0.1", port = port) { module(config) }
            server.start(wait = false)
            try {
                val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/health")).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())

                assertEquals(200, response.statusCode())
                assertEquals(
                    before + 1,
                    awaitWriterThreadCount(before + 1),
                    "the store must open one writer thread at the start",
                )
            } finally {
                server.stop(gracePeriodMillis = 0, timeoutMillis = 1000)
            }
            assertEquals(before, awaitWriterThreadCount(before), "the store must close its writer thread at the stop")
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
            pollIntervalSeconds = 60,
        )
        val server = embeddedServer(Netty, host = "127.0.0.1", port = port) { module(config) }
        server.start(wait = false)
        try {
            val client = HttpClient.newHttpClient()
            val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/health")).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())

            assertEquals(200, response.statusCode())
            assertEquals(
                before + 1,
                awaitWriterThreadCount(before + 1),
                "the database must still open, although the sweep failed",
            )
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 1000)
        }
        assertEquals(before, awaitWriterThreadCount(before), "the writer thread must not survive the stop")
        assertTrue(
            brokenBytes.contentEquals(secretsFile.readBytes()),
            "the broken file must stay exactly as it was",
        )
    }

    // Issue #18: the new route survives a restart. `module()` installs
    // `GET /api/apps` on each start, with no flag, thus the route reads the
    // one persisted file across the two starts of one folder.
    @Test
    fun `GET api-apps answers across two starts and stops of the server, in one folder`() {
        val client = HttpClient.newHttpClient()

        repeat(2) { round ->
            val port = freePort()
            val config = MonitorConfig(
                mode = "prod",
                port = port,
                dataDir = dataDir.absolutePath,
                settleLagSeconds = 60,
                retentionDays = 395,
                pollIntervalSeconds = 60,
            )
            val server = embeddedServer(Netty, host = "127.0.0.1", port = port) { module(config) }
            server.start(wait = false)
            try {
                val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/apps")).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())

                assertEquals(200, response.statusCode())
                val rowCount = Json.parseToJsonElement(response.body()).jsonArray.size
                assertEquals(round, rowCount, "the row of the first start must still be there for the second start")
            } finally {
                server.stop(gracePeriodMillis = 0, timeoutMillis = 1000)
            }

            if (round == 0) {
                insertLifecycleApp()
            }
        }
    }

    // Writes one app row between the two starts, on a fresh SqliteDatabase
    // handle. The server of round 0 already closed, thus this call opens
    // no second writer next to a live one.
    private fun insertLifecycleApp() = runBlocking {
        val database = SqliteDatabase.open(dataDir.absolutePath)
        try {
            database.write { writer ->
                writer.prepareStatement(
                    "INSERT INTO app (name, database_name, collection_name, created_at) " +
                        "VALUES (?, ?, ?, ?)",
                ).use { insert ->
                    insert.setString(1, "lifecycle-app")
                    insert.setString(2, "db")
                    insert.setString(3, "octometer_events")
                    insert.setLong(4, 1_700_000_000_000L)
                    insert.executeUpdate()
                }
            }
        } finally {
            database.close()
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    // SQLite MAJOR 3 of correction round 1: the coroutine debug mode of
    // the test JVM renames the writer thread to
    // "octometer-sqlite-writer @coroutine#N" while a coroutine runs on
    // it, for example the daily backup. The old exact match then read 0
    // during a slow backup. startsWith reads the thread whichever
    // coroutine, or none, runs on it.
    private fun writerThreadCount(): Int =
        Thread.getAllStackTraces().keys.count { it.name.startsWith(WRITER_THREAD_NAME) }

    // Issue #55: a busy CI runner needs a short moment to schedule the
    // writer thread's first task, or to end that thread after close().
    // This polls for up to two seconds, the same rule as
    // MonitorServicesTest.awaitWriterThreadCount, so the count settles
    // before the test reads it.
    private fun awaitWriterThreadCount(expected: Int, timeoutMillis: Long = 2_000): Int {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        var count = writerThreadCount()
        while (count != expected && System.nanoTime() < deadline) {
            Thread.sleep(20)
            count = writerThreadCount()
        }
        return count
    }
}
