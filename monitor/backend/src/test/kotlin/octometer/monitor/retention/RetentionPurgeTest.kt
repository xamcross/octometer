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
import kotlin.system.measureTimeMillis
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

    // SQLite MAJOR A of correction round 2: dataDir sits under root, a
    // nested folder, so the sibling backups folder of issue #55 stays
    // inside root and never lands at the system temp root.
    private val root = Files.createTempDirectory("octometer-retention-purge-test-").toFile()
        .also { registerTempRoot(it) }
    private val dataDir = File(root, "data")
    private lateinit var database: SqliteDatabase
    private var appId: Long = 0

    @BeforeTest
    fun setUp() = runBlocking {
        database = SqliteDatabase.open(dataDir.absolutePath)
        appId = insertApp(database, "demo")
    }

    @AfterTest
    fun tearDown() {
        database.close()
        root.deleteRecursively()
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

        val walFile = File(dataDir, "octometer.db-wal")
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

    // --- app_path aging (issue #205, the correction round of 2026-09-27) ---

    @Test
    fun `purgeOnce deletes an app_path row whose event rows are all purged`() = runBlocking {
        val cutoff = NOW - RETENTION_DAYS * DAY_MILLIS
        insertEvent("old-click", cutoff - DAY_MILLIS, kind = 0, path = "/checkout")
        insertAppPath("/checkout")

        RetentionPurge(database, fixedClock(NOW)).purgeOnce(RETENTION_DAYS)

        assertEquals(emptySet(), remainingAppPathValues())
    }

    @Test
    fun `purgeOnce keeps an app_path row whose event row is not purged`() = runBlocking {
        insertEvent("new-click", NOW, kind = 0, path = "/checkout")
        insertAppPath("/checkout")

        RetentionPurge(database, fixedClock(NOW)).purgeOnce(RETENTION_DAYS)

        assertEquals(setOf("/checkout"), remainingAppPathValues())
    }

    // The aging rule of the correction round matches on the event
    // table as it stands after the purge, not on a separate "was this
    // value ever seen" flag. A value with no matching event row at all
    // ages out too, the same as a value whose last event row just
    // purged.
    @Test
    fun `purgeOnce deletes an app_path row of a value with no matching event row at all`() = runBlocking {
        insertAppPath("/never-matched")

        RetentionPurge(database, fixedClock(NOW)).purgeOnce(RETENTION_DAYS)

        assertEquals(emptySet(), remainingAppPathValues())
    }

    // BLOCKER 1 of the Kotlin and SQLite review of pull request #232:
    // the statement must read the event rows of the app one time in
    // total, never once for each app_path row. This runs the same SQL
    // constant that RetentionPurge runs (the database brief rule), so
    // a later change back to a correlated form fails this test.
    @Test
    fun `the app_path aging statement plans one list subquery, not a correlated scan of event`() = runBlocking {
        val plan = database.write { writer ->
            writer.prepareStatement("EXPLAIN QUERY PLAN $DELETE_STALE_APP_PATH_SQL").use { statement ->
                statement.setLong(1, appId)
                statement.setLong(2, appId)
                statement.executeQuery().use { result ->
                    val lines = mutableListOf<String>()
                    while (result.next()) lines += result.getString("detail")
                    lines
                }
            }
        }
        println("EXPLAIN QUERY PLAN for the app_path aging delete: $plan")
        assertTrue(
            plan.none { line -> line.contains("CORRELATED") },
            "the plan must hold no correlated subquery: $plan",
        )
        assertTrue(
            plan.any { line -> line.contains("LIST SUBQUERY") },
            "the plan must build the path list one time: $plan",
        )
        assertTrue(
            plan.any { line -> line.contains("SEARCH event USING INDEX event_session (app_id=?)") },
            "the list subquery must scan the app's own event rows one time: $plan",
        )
    }

    // The reviewer's probe measured 5 305 ms for the correlated form,
    // and 35.8 ms for the "NOT IN" form, with 50 000 events and 400
    // app_path rows. This repeats that setup, and it keeps a generous
    // bound (2 s) so a slower CI runner does not flake; the elapsed
    // time is printed for the pull request comment.
    @Test
    fun `purgeOnce ages 400 app_path rows of 50 000 events well under the old correlated cost`() = runBlocking {
        repeat(50_000) { index -> insertEvent("evt-$index", NOW, kind = index % 2, path = "/path-${index % 400}") }
        repeat(400) { index -> insertAppPath("/path-$index") }
        insertAppPath("/stale-value")

        val elapsedMillis = measureTimeMillis {
            RetentionPurge(database, fixedClock(NOW)).purgeOnce(RETENTION_DAYS)
        }

        println("purgeOnce with 50 000 events and 400 app_path rows took $elapsedMillis ms.")
        assertEquals(400, remainingAppPathValues().size, "the 400 matched values must stay")
        assertTrue(
            elapsedMillis < 2_000,
            "expected the aging delete to finish well under 2 s, took $elapsedMillis ms",
        )
    }

    // --- gap aging (issue #227) ---

    @Test
    fun `purgeOnce deletes a gap row whose to_ts is older than the retention time`() = runBlocking {
        val cutoff = NOW - RETENTION_DAYS * DAY_MILLIS
        insertGap(appId, cutoff - DAY_MILLIS, cutoff - 1_000)

        RetentionPurge(database, fixedClock(NOW)).purgeOnce(RETENTION_DAYS)

        assertEquals(emptyList(), remainingGaps())
    }

    @Test
    fun `purgeOnce keeps a gap row whose to_ts is inside the retention time`() = runBlocking {
        val cutoff = NOW - RETENTION_DAYS * DAY_MILLIS
        insertGap(appId, cutoff - DAY_MILLIS, cutoff + DAY_MILLIS)

        RetentionPurge(database, fixedClock(NOW)).purgeOnce(RETENTION_DAYS)

        assertEquals(listOf(cutoff - DAY_MILLIS to cutoff + DAY_MILLIS), remainingGaps())
    }

    // The app scope of the delete: while the purge deletes an old gap
    // row of one app, a fresh gap row of another app stays. The loop
    // must apply each app's own id to its own delete call, never the
    // id of a different app.
    @Test
    fun `purgeOnce keeps a fresh gap row of another app`() = runBlocking {
        val cutoff = NOW - RETENTION_DAYS * DAY_MILLIS
        insertGap(appId, cutoff - DAY_MILLIS, cutoff - 1_000)
        val otherAppId = insertApp(database, "other-app")
        insertGap(otherAppId, cutoff - DAY_MILLIS, cutoff + DAY_MILLIS)

        RetentionPurge(database, fixedClock(NOW)).purgeOnce(RETENTION_DAYS)

        assertEquals(emptyList(), remainingGaps(appId))
        assertEquals(listOf(cutoff - DAY_MILLIS to cutoff + DAY_MILLIS), remainingGaps(otherAppId))
    }

    private fun fixedClock(millis: Long): Clock = Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC)

    private suspend fun insertGap(forAppId: Long, fromTs: Long, toTs: Long) {
        database.write { writer ->
            writer.prepareStatement(
                "INSERT INTO gap (app_id, from_ts, to_ts) VALUES (?, ?, ?)",
            ).use { insert ->
                insert.setLong(1, forAppId)
                insert.setLong(2, fromTs)
                insert.setLong(3, toTs)
                insert.executeUpdate()
            }
        }
    }

    private suspend fun remainingGaps(forAppId: Long = appId): List<Pair<Long, Long>> =
        database.read { reader ->
            reader.prepareStatement("SELECT from_ts, to_ts FROM gap WHERE app_id = ? ORDER BY from_ts").use { select ->
                select.setLong(1, forAppId)
                select.executeQuery().use { result ->
                    val rows = mutableListOf<Pair<Long, Long>>()
                    while (result.next()) rows += result.getLong(1) to result.getLong(2)
                    rows
                }
            }
        }

    private suspend fun insertAppPath(path: String) {
        database.write { writer ->
            writer.prepareStatement(
                "INSERT INTO app_path (app_id, path, first_seen_at) VALUES (?, ?, ?)",
            ).use { insert ->
                insert.setLong(1, appId)
                insert.setString(2, path)
                insert.setLong(3, NOW)
                insert.executeUpdate()
            }
        }
    }

    private suspend fun remainingAppPathValues(): Set<String> =
        database.read { reader ->
            reader.createStatement().use { statement ->
                statement.executeQuery("SELECT path FROM app_path").use { result ->
                    val values = mutableSetOf<String>()
                    while (result.next()) values += result.getString(1)
                    values
                }
            }
        }

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

    private suspend fun insertEvent(eventId: String, ts: Long, kind: Int, path: String? = null) {
        database.write { writer ->
            writer.prepareStatement(
                "INSERT INTO event (app_id, event_id, ts, element, session_id, user_id, kind, path) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { insert ->
                insert.setLong(1, appId)
                insert.setString(2, eventId)
                insert.setLong(3, ts)
                insert.setString(4, "checkout.save")
                insert.setString(5, "session-1")
                insert.setString(6, "user-1")
                insert.setInt(7, kind)
                insert.setString(8, path)
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
