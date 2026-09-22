package octometer.monitor

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import octometer.monitor.registry.SecretStore
import octometer.monitor.registry.allowlistedSrvUri
import octometer.monitor.store.SqliteDatabase
import org.sqlite.SQLiteException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val WRITER_THREAD_NAME = "octometer-sqlite-writer"
private const val PURGE_THREAD_NAME = "octometer-retention-purge"

/**
 * MAJOR 4 of the security review: a secret whose app id has no app row
 * must not stay on the disk for ever. MonitorServices.open() sweeps it
 * away at the start, and writes one log line with the removed count.
 */
class MonitorServicesTest {

    private val root = Files.createTempDirectory("octometer-monitor-services-test-").toFile()
    private val dataDir = File(root, "data").absolutePath

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `open removes each secret whose app id has no app row, and keeps the rest`() = runBlocking {
        val appId = seedOneAppRow()
        val secretStore = SecretStore(dataDir)
        secretStore.put(appId, allowlistedSrvUri())
        secretStore.put(999_999L, allowlistedSrvUri())

        val services = MonitorServices.open(prodConfig(dataDir = dataDir))
        try {
            assertTrue(services.secretStore.contains(appId), "the secret of an existing app row must stay")
            assertFalse(services.secretStore.contains(999_999L), "the orphan secret must be gone")
        } finally {
            services.close()
        }
    }

    // MAJOR 5 (second Ktor review) and MAJOR 2 (second security review).
    // A broken secrets/apps.json file must not stop the start. The start
    // must not leave the database open. open() must never overwrite a
    // file that it could not read.
    @Test
    fun `open skips the sweep and still returns usable services, when the secret file is broken at the start`() =
        runBlocking {
            val secretsDir = File(root, "secrets").apply { mkdirs() }
            val secretsFile = File(secretsDir, "apps.json")
            val brokenBytes = "{ this is not valid json".toByteArray(Charsets.UTF_8)
            secretsFile.writeBytes(brokenBytes)

            val services = MonitorServices.open(prodConfig(dataDir = dataDir))
            try {
                assertTrue(
                    brokenBytes.contentEquals(secretsFile.readBytes()),
                    "the broken file must stay exactly as it was",
                )
            } finally {
                services.close()
            }
        }

    // MAJOR 1 (third security review): the old runCatching caught every
    // Throwable around the sweep. A database defect then only skipped
    // the sweep; it never stopped the start. open() must now let a
    // database defect reach the outer catch, close the store, and throw.
    //
    // Issue #59: the purge job now starts before the sweep runs, so this
    // failure also proves that a throw in open() stops the purge job, not
    // only the database. It leaves neither thread behind.
    @Test
    fun `open throws and leaves no writer thread and no purge thread, when the app table is missing`() =
        runBlocking {
            val writerBaseline = awaitThreadCount(WRITER_THREAD_NAME, 0)
            val purgeBaseline = awaitThreadCount(PURGE_THREAD_NAME, 0)
            val database = SqliteDatabase.open(dataDir)
            try {
                database.write { writer ->
                    writer.createStatement().use { statement -> statement.execute("DROP TABLE app") }
                }
            } finally {
                database.close()
            }
            val writerBefore = awaitThreadCount(WRITER_THREAD_NAME, writerBaseline)
            val purgeBefore = awaitThreadCount(PURGE_THREAD_NAME, purgeBaseline)

            assertFailsWith<SQLiteException> {
                MonitorServices.open(prodConfig(dataDir = dataDir))
            }

            assertEquals(
                writerBefore,
                awaitThreadCount(WRITER_THREAD_NAME, writerBefore),
                "a failed open must leave no writer thread",
            )
            assertEquals(
                purgeBefore,
                awaitThreadCount(PURGE_THREAD_NAME, purgeBefore),
                "a failed open must leave no purge thread",
            )
        }

    // Issue #59, step 4: open() starts the purge job, and close() stops it.
    // The purge thread starts on its own coroutine, a short time after
    // open() returns, not at once, so this polls for its arrival too, not
    // only for its exit after close().
    @Test
    fun `open starts the purge thread, and close stops it`() = runBlocking {
        val baseline = awaitThreadCount(PURGE_THREAD_NAME, 0)

        val services = MonitorServices.open(prodConfig(dataDir = dataDir))
        val duringOpen = awaitThreadCount(PURGE_THREAD_NAME, baseline + 1)

        services.close()

        assertEquals(baseline + 1, duringOpen, "open() starts exactly one purge thread")
        assertEquals(
            baseline,
            awaitThreadCount(PURGE_THREAD_NAME, baseline),
            "close() stops the purge thread",
        )
    }

    private fun threadCount(name: String): Int =
        Thread.getAllStackTraces().keys.count { it.name == name }

    // A closed dispatcher ends its thread a short time after close()
    // returns, not at once. This polls for up to two seconds, so the
    // count settles before the test reads it, and the check stays exact.
    private fun awaitThreadCount(name: String, expected: Int, timeoutMillis: Long = 2_000): Int {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        var count = threadCount(name)
        while (count != expected && System.nanoTime() < deadline) {
            Thread.sleep(20)
            count = threadCount(name)
        }
        return count
    }

    private suspend fun seedOneAppRow(): Long {
        val database = SqliteDatabase.open(dataDir)
        val appId = try {
            database.write { writer ->
                writer.prepareStatement(
                    "INSERT INTO app (name, database_name, collection_name, created_at) VALUES (?, ?, ?, ?)",
                ).use { insert ->
                    insert.setString(1, "demo")
                    insert.setString(2, "db")
                    insert.setString(3, "octometer_events")
                    insert.setLong(4, System.currentTimeMillis())
                    insert.executeUpdate()
                }
                writer.createStatement().use { statement ->
                    statement.executeQuery("SELECT last_insert_rowid()").use { result ->
                        result.next()
                        result.getLong(1)
                    }
                }
            }
        } finally {
            database.close()
        }
        return appId
    }
}
