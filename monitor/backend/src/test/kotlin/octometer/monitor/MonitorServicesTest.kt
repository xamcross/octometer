package octometer.monitor

import ch.qos.logback.classic.Level
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import octometer.monitor.mongo.PollOutcome
import octometer.monitor.poll.PollCycle
import octometer.monitor.registry.SecretStore
import octometer.monitor.registry.allowlistedSrvUri
import octometer.monitor.store.SqliteDatabase
import org.junit.jupiter.api.Assumptions
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

    // Issue #142: open() starts a purge thread and a writer thread. A
    // native SQLite file handle can outlive close() for a short time on
    // Windows. The eager delete below can then lose that race. A call to
    // registerTempRoot gives the folder a second chance for a delete, at
    // JVM exit.
    private val root = Files.createTempDirectory("octometer-monitor-services-test-").toFile()
        .also { registerTempRoot(it) }
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

        // appId has no next_poll_at, so it is due at once. A stub
        // PollCycle gives this test no outbound MongoDB connection
        // (issue #17, decision 5; MAJOR 4 of the Kotlin review).
        val stubCycle = PollCycle { _, _ -> PollOutcome(eventsStored = 0, pagesRead = 0, cursor = null) }
        val services = MonitorServices.open(prodConfig(dataDir = dataDir), pollCycle = stubCycle)
        try {
            assertTrue(services.secretStore.contains(appId), "the secret of an existing app row must stay")
            assertFalse(services.secretStore.contains(999_999L), "the orphan secret must be gone")
        } finally {
            services.close()
        }
    }

    // Issue #141: a kill of the process between the temporary write and
    // the atomic move can leave a leftover apps-<random>.json.tmp file in
    // the secrets folder. open() removes it at the start, before the
    // first write.
    @Test
    fun `open removes a leftover apps-123_json_tmp file from the secrets folder, and leaves apps json unchanged`() =
        runBlocking {
            val secretsDir = File(root, "secrets").apply { mkdirs() }
            val secretsFile = File(secretsDir, "apps.json")
            val originalBytes = "{}".toByteArray(Charsets.UTF_8)
            secretsFile.writeBytes(originalBytes)
            val leftoverFile = File(secretsDir, "apps-123.json.tmp")
            leftoverFile.writeText("stray")
            val otherFile = File(secretsDir, "apps-backup.json")
            otherFile.writeText("keep me")

            val services = MonitorServices.open(prodConfig(dataDir = dataDir))
            try {
                assertFalse(leftoverFile.exists(), "the leftover temporary file must be gone after the start")
                assertTrue(otherFile.exists(), "a file with a different name must stay")
                assertTrue(
                    originalBytes.contentEquals(secretsFile.readBytes()),
                    "apps.json must stay unchanged byte for byte",
                )
            } finally {
                services.close()
            }
        }

    // Acceptance criterion of issue #141: a locked leftover file gives one
    // warning, and the monitor still starts. No log line, at any level,
    // holds a part of a connection string.
    @Test
    fun `open starts, and gives one warning with no connection string, when a leftover temporary file is locked`() =
        runBlocking {
            // Windows refuses to delete a file while a FileChannel lock
            // holds it open. A Linux advisory lock does not block a
            // delete, so this test would pass by accident there.
            Assumptions.assumeTrue(
                System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true),
                "the OS must refuse to delete a locked file",
            )
            val secretsDir = File(root, "secrets").apply { mkdirs() }
            val leftoverFile = File(secretsDir, "apps-456.json.tmp")
            leftoverFile.writeText(allowlistedSrvUri())

            RandomAccessFile(leftoverFile, "rw").use { handle ->
                val lock = handle.channel.lock()
                try {
                    val (services, events) = captureLogEvents { MonitorServices.open(prodConfig(dataDir = dataDir)) }
                    try {
                        assertTrue(leftoverFile.exists(), "a locked leftover file must stay")
                        val warnings = events.filter { event -> event.level == Level.WARN }
                        assertTrue(warnings.isNotEmpty(), "expected at least one warning")
                        val combinedText = events.joinToString(" ") { event -> event.formattedMessage }
                        assertFalse(
                            combinedText.contains(allowlistedSrvUri()),
                            "no log line may hold a connection string",
                        )
                    } finally {
                        services.close()
                    }
                } finally {
                    lock.release()
                }
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

    // Issue #17, decision 5 (MAJOR 4 and MAJOR 5 of the Kotlin review):
    // open() binds the poll scheduler to the application lifecycle. A
    // stub PollCycle proves the wire-up, with no outbound MongoDB
    // connection. Since the stub never runs, the real MongoAppReader
    // that open() still builds keeps no client, so close() has nothing
    // of it to close.
    //
    // BLOCKER 1 of the second Kotlin review: the count must come from a
    // real event, not from a fixed sleep. pollIntervalSeconds is 1, so
    // the first tick is due at once, and a second tick is due one
    // second later. Each latch counts down inside the stub call itself,
    // so the wait ends on the real event, with a bound, never on a
    // guess of the clock.
    @Test
    fun `open runs a stub poll cycle for a due app, and close stops it with no further call`() = runBlocking {
        val appId = seedOneAppRow()
        val secretStore = SecretStore(dataDir)
        secretStore.put(appId, allowlistedSrvUri())
        val calls = AtomicInteger(0)
        val firstCallLatch = CountDownLatch(1)
        val secondCallLatch = CountDownLatch(1)
        val stubCycle = PollCycle { _, _ ->
            val count = calls.incrementAndGet()
            if (count == 1) firstCallLatch.countDown()
            if (count == 2) secondCallLatch.countDown()
            PollOutcome(eventsStored = 0, pagesRead = 0, cursor = null)
        }

        val (services, events) = captureLogEvents {
            MonitorServices.open(prodConfig(dataDir = dataDir, pollIntervalSeconds = 1), pollCycle = stubCycle)
        }
        val startedInTime = firstCallLatch.await(2, TimeUnit.SECONDS)
        assertTrue(startedInTime, "open() must start the scheduler within the wait bound")
        assertEquals(1, calls.get(), "open() starts the scheduler: the stub cycle ran one time")

        services.close()
        // A running scheduler would poll a due app again one second
        // later. This waits, with a bound, for that second call. It
        // must never arrive, because close() already stopped the loop.
        val secondCallArrived = secondCallLatch.await(2_500, TimeUnit.MILLISECONDS)
        assertFalse(secondCallArrived, "close() stops the scheduler; the stub runs no more")
        assertEquals(1, calls.get(), "close() stops the scheduler; the stub runs no more")
        assertTrue(
            events.none { event -> event.loggerName.contains("mongo", ignoreCase = true) },
            "the stub never runs the real reader, so no Mongo log line appears",
        )
    }

    // Correction round 1 of issue #59, decision 1: close() cancels the
    // purge, then joins it, with no fixed time bound. A second raw
    // connection holds an exclusive lock, so the first purge batch of
    // 100 000 old rows blocks inside SQLite. close() then runs while
    // that batch write is still in progress. The old 300 ms bound let
    // close() return early, and a stray write then reached the closed
    // connection after close() had already returned. This test asserts
    // that no ERROR line comes after close() returns, that no thread
    // stays, and that PRAGMA quick_check still answers "ok".
    @Test
    fun `close during a purge of 100 000 old rows ends without an exception, with no stale thread, and quick_check gives ok`() =
        runBlocking {
            seedOldEventRows(100_000)
            val writerBaseline = awaitThreadCount(WRITER_THREAD_NAME, 0)
            val purgeBaseline = awaitThreadCount(PURGE_THREAD_NAME, 0)

            val dbFile = File(dataDir, "octometer.db")
            val lockConnection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.absolutePath)
            lockConnection.createStatement().use { it.execute("BEGIN EXCLUSIVE") }

            val services = MonitorServices.open(prodConfig(dataDir = dataDir, retentionDays = 1))
            // Gives the purge time to dispatch its first batch, and to
            // block on the lock above.
            delay(200)

            val (closeReturnedAt, errorEvents) = captureErrorLogEvents {
                services.close()
                val returnedAt = System.currentTimeMillis()
                // The blocked batch throws only once busy_timeout elapses
                // (5000 ms, from SqlitePragmas). This waits past that
                // point, so a stray write that outlives close() has time
                // to log its error before the assertion below runs.
                delay(5_300)
                returnedAt
            }

            lockConnection.createStatement().use { it.execute("COMMIT") }
            lockConnection.close()

            val strayErrors = errorEvents.filter { event -> event.timeStamp > closeReturnedAt }
            assertTrue(strayErrors.isEmpty(), "close() must leave no ERROR log line logged after it returns")
            assertEquals(
                writerBaseline,
                awaitThreadCount(WRITER_THREAD_NAME, writerBaseline),
                "close() must leave no writer thread",
            )
            assertEquals(
                purgeBaseline,
                awaitThreadCount(PURGE_THREAD_NAME, purgeBaseline),
                "close() must leave no purge thread",
            )
            assertEquals("ok", quickCheck())
        }

    private suspend fun seedOldEventRows(count: Int) {
        val database = SqliteDatabase.open(dataDir)
        try {
            val appId = database.write { writer ->
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
            database.write { writer ->
                writer.autoCommit = false
                try {
                    writer.prepareStatement(
                        "INSERT INTO event (app_id, event_id, ts, element, session_id, user_id, kind) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    ).use { insert ->
                        for (index in 0 until count) {
                            insert.setLong(1, appId)
                            insert.setString(2, "old-$index")
                            insert.setLong(3, 0L)
                            insert.setString(4, "checkout.save")
                            insert.setString(5, "session-1")
                            insert.setString(6, "user-1")
                            insert.setInt(7, index % 2)
                            insert.addBatch()
                        }
                        insert.executeBatch()
                    }
                    writer.commit()
                } finally {
                    writer.autoCommit = true
                }
            }
        } finally {
            database.close()
        }
    }

    private suspend fun quickCheck(): String {
        val database = SqliteDatabase.open(dataDir)
        return try {
            database.write { writer ->
                writer.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA quick_check").use { result ->
                        result.next()
                        result.getString(1)
                    }
                }
            }
        } finally {
            database.close()
        }
    }

    // SQLite MAJOR 3 of correction round 1: startsWith, not ==. See the
    // comment of the same helper in ApplicationLifecycleTest.
    private fun threadCount(name: String): Int =
        Thread.getAllStackTraces().keys.count { it.name.startsWith(name) }

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
