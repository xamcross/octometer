package octometer.monitor.retention

import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import octometer.monitor.registerTempRoot
import octometer.monitor.store.SqliteDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val DAY_MILLIS = 86_400_000L
private const val RETENTION_DAYS = 395

// A fixed instant, so each test gives a reproducible cutoff (issue #59,
// step 4: "inject a Clock for the tests").
private const val NOW = 1_700_000_000_000L

// Issue #59, steps 2 and 3 (D15): the purge deletes each event older than
// retentionDays. It deletes a click row (kind = 0) and a session start
// row (kind = 1) alike. It runs in chunks, then checkpoints the wal file.
// Each test uses its own temporary folder, never the real data folder.
class RetentionPurgeTest {

    private val tempDir = Files.createTempDirectory("octometer-retention-purge-test-").toFile()
        .also { registerTempRoot(it) }
    private lateinit var database: SqliteDatabase
    private var appId: Long = 0

    @BeforeTest
    fun setUp() = runBlocking {
        database = SqliteDatabase.open(tempDir.absolutePath)
        appId = insertApp(database, "demo")
    }

    @AfterTest
    fun tearDown() {
        database.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun `purgeOnce deletes an old click row and an old session start row`() = runBlocking {
        val cutoff = NOW - RETENTION_DAYS * DAY_MILLIS
        insertEvent("old-click", cutoff - DAY_MILLIS, kind = 0)
        insertEvent("old-start", cutoff - DAY_MILLIS, kind = 1)
        insertEvent("new-click", NOW, kind = 0)

        RetentionPurge(database, fixedClock(NOW)).purgeOnce(RETENTION_DAYS)

        assertEquals(setOf("new-click"), remainingEventIds())
    }

    // Boundary rule of issue #59, step 5 of the report: an event of exactly
    // retentionDays age is not "older than" the limit. The purge keeps it.
    @Test
    fun `an event of exactly retentionDays age stays`() = runBlocking {
        val cutoff = NOW - RETENTION_DAYS * DAY_MILLIS
        insertEvent("at-cutoff", cutoff, kind = 0)

        RetentionPurge(database, fixedClock(NOW)).purgeOnce(RETENTION_DAYS)

        assertEquals(setOf("at-cutoff"), remainingEventIds())
    }

    @Test
    fun `an event one second older than retentionDays is deleted`() = runBlocking {
        val cutoff = NOW - RETENTION_DAYS * DAY_MILLIS
        insertEvent("one-second-older", cutoff - 1_000, kind = 0)

        RetentionPurge(database, fixedClock(NOW)).purgeOnce(RETENTION_DAYS)

        assertTrue(remainingEventIds().isEmpty())
    }

    @Test
    fun `an event one second younger than retentionDays stays`() = runBlocking {
        val cutoff = NOW - RETENTION_DAYS * DAY_MILLIS
        insertEvent("one-second-younger", cutoff + 1_000, kind = 0)

        RetentionPurge(database, fixedClock(NOW)).purgeOnce(RETENTION_DAYS)

        assertEquals(setOf("one-second-younger"), remainingEventIds())
    }

    // Issue #59, step 2 and its acceptance criterion: the purge deletes in
    // chunks of 10 000 rows. This inserts more than one chunk of old rows.
    // The test then proves the loop runs more than one batch.
    @Test
    fun `purgeOnce deletes every old row, across more than one batch of 10 000`() = runBlocking {
        val cutoff = NOW - RETENTION_DAYS * DAY_MILLIS
        val oldCount = 10_003
        repeat(oldCount) { index -> insertEvent("old-$index", cutoff - DAY_MILLIS, kind = index % 2) }
        insertEvent("new-click", NOW, kind = 0)

        RetentionPurge(database, fixedClock(NOW)).purgeOnce(RETENTION_DAYS)

        assertEquals(setOf("new-click"), remainingEventIds())
    }

    // Correction round 1, decision 3: the batch size is 10 000. This test
    // fails when that value changes, because the row count and the
    // expected write count each name 10 000 in a literal, not a shared
    // constant. Two batches of 10 000 and 1 rows delete 10 001 old rows,
    // plus one write call for the checkpoint, for 3 calls in total.
    @Test
    fun `purgeOnce writes exactly 3 times for 10 001 old rows, with a batch size of 10 000`() = runBlocking {
        val cutoff = NOW - RETENTION_DAYS * DAY_MILLIS
        val oldCount = 10_001
        repeat(oldCount) { index -> insertEvent("old-$index", cutoff - DAY_MILLIS, kind = index % 2) }

        val writeCount = AtomicInteger(0)
        RetentionPurge(database, fixedClock(NOW), onWrite = { writeCount.incrementAndGet() })
            .purgeOnce(RETENTION_DAYS)

        assertEquals(3, writeCount.get(), "two delete batches, plus one checkpoint call")
        assertTrue(remainingEventIds().isEmpty())
    }

    // Issue #59, step 3: the purge runs PRAGMA wal_checkpoint(TRUNCATE)
    // after. That pragma sets the wal file back to 0 bytes. This checks
    // the file on the disk, not only the return value of the pragma.
    @Test
    fun `purgeOnce empties the wal file after the checkpoint`() = runBlocking {
        insertEvent("old-click", NOW - (RETENTION_DAYS + 1) * DAY_MILLIS, kind = 0)

        RetentionPurge(database, fixedClock(NOW)).purgeOnce(RETENTION_DAYS)

        val walFile = File(tempDir, "octometer.db-wal")
        assertTrue(walFile.exists(), "the wal file exists after a write")
        assertEquals(0L, walFile.length(), "wal_checkpoint(TRUNCATE) empties the wal file")
    }

    // Issue #59, "the delete statement uses an index": this records the
    // query plan of the batch delete for the pull request text, and it
    // fails when the plan stops using the covering index.
    @Test
    fun `the delete statement scans the covering index event_session`() = runBlocking {
        val plan = database.write { writer ->
            writer.prepareStatement(
                "EXPLAIN QUERY PLAN SELECT rowid FROM event WHERE ts < ? LIMIT 10000",
            ).use { statement ->
                statement.setLong(1, NOW)
                statement.executeQuery().use { result ->
                    val lines = mutableListOf<String>()
                    while (result.next()) lines += result.getString("detail")
                    lines
                }
            }
        }
        println("EXPLAIN QUERY PLAN for the purge delete: $plan")
        assertTrue(
            plan.any { line -> line.contains("COVERING INDEX event_session") },
            "the plan must scan the covering index event_session",
        )
    }

    private fun fixedClock(millis: Long): Clock = Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC)

    private suspend fun remainingEventIds(): Set<String> =
        database.read { reader ->
            reader.createStatement().use { statement ->
                statement.executeQuery("SELECT event_id FROM event").use { result ->
                    val ids = mutableSetOf<String>()
                    while (result.next()) ids += result.getString(1)
                    ids
                }
            }
        }

    private suspend fun insertEvent(eventId: String, ts: Long, kind: Int) {
        database.write { writer ->
            writer.prepareStatement(
                "INSERT INTO event (app_id, event_id, ts, element, session_id, user_id, kind) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
            ).use { insert ->
                insert.setLong(1, appId)
                insert.setString(2, eventId)
                insert.setLong(3, ts)
                insert.setString(4, "checkout.save")
                insert.setString(5, "session-1")
                insert.setString(6, "user-1")
                insert.setInt(7, kind)
                insert.executeUpdate()
            }
        }
    }

    private suspend fun insertApp(database: SqliteDatabase, name: String): Long =
        database.write { writer ->
            writer.prepareStatement(
                "INSERT INTO app (name, database_name, collection_name, created_at) VALUES (?, ?, ?, ?)",
            ).use { insert ->
                insert.setString(1, name)
                insert.setString(2, "db")
                insert.setString(3, "octometer_events")
                insert.setLong(4, NOW)
                insert.executeUpdate()
            }
            writer.createStatement().use { statement ->
                statement.executeQuery("SELECT last_insert_rowid()").use { result ->
                    result.next()
                    result.getLong(1)
                }
            }
        }
}
