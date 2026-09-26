package octometer.monitor.store

import java.io.File
import java.nio.file.Files
import java.sql.SQLException
import kotlinx.coroutines.runBlocking
import octometer.monitor.registerTempRoot
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Step 6 of issue #9 (D4): one BEGIN IMMEDIATE transaction commits a page
// and the cursor. Each test uses its own temporary folder, never the real
// data folder.
class EventStoreTest {

    // SQLite MAJOR 1 of correction round 1: dataDir sits under root, so
    // the sibling backups folder of issue #55 stays inside root.
    private val root = Files.createTempDirectory("octometer-event-store-test-").toFile().also { registerTempRoot(it) }
    private val dataDir = File(root, "data")
    private lateinit var database: SqliteDatabase
    private lateinit var store: EventStore
    private var appId: Long = 0

    @BeforeTest
    fun setUp() = runBlocking {
        database = SqliteDatabase.open(dataDir.absolutePath)
        store = EventStore(database)
        appId = insertApp(database, "demo")
    }

    @AfterTest
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun `commitPage inserts each event and moves the cursor`() = runBlocking {
        store.commitPage(appId, listOf(sampleEvent("e1")), cursor = "cursor-1")

        assertEquals(1, countEvents(database, appId))
        assertEquals("cursor-1", readCursor(database, appId))
    }

    // MINOR 3 of correction round 1 (SQLite and data engineer): the replay
    // test must also assert the cursor move that D4 asks for.
    @Test
    fun `a replay of the same page adds 0 rows and still moves the cursor`() = runBlocking {
        val page = listOf(sampleEvent("e1"), sampleEvent("e2"))
        store.commitPage(appId, page, cursor = "cursor-1")

        store.commitPage(appId, page, cursor = "cursor-2")

        assertEquals(2, countEvents(database, appId))
        assertEquals("cursor-2", readCursor(database, appId))
    }

    // BLOCKER 1 of correction round 1 (Kotlin backend engineer): the bad
    // page now holds a good event first. The assertion on the event count
    // proves the rollback of the whole page, not only the call order.
    @Test
    fun `a failed insert leaves the cursor as it was and rolls back the good event too`() = runBlocking {
        store.commitPage(appId, listOf(sampleEvent("e1")), cursor = "cursor-1")

        val badPage = listOf(sampleEvent("e2"), sampleEvent("e3", userId = ""))
        assertFailsWith<SQLException> {
            store.commitPage(appId, badPage, cursor = "cursor-2")
        }

        assertEquals("cursor-1", readCursor(database, appId))
        assertEquals(1, countEvents(database, appId))
    }

    // MAJOR 4 of correction round 1 (Kotlin backend engineer): the store
    // must commit again after one failed page. The ROLLBACK must return the
    // writer connection to a plain, non-transactional state.
    @Test
    fun `the store commits again after one failed page`() = runBlocking {
        store.commitPage(appId, listOf(sampleEvent("e1")), cursor = "cursor-1")

        val badPage = listOf(sampleEvent("e2"), sampleEvent("e3", userId = ""))
        assertFailsWith<SQLException> {
            store.commitPage(appId, badPage, cursor = "cursor-2")
        }

        store.commitPage(appId, listOf(sampleEvent("e4")), cursor = "cursor-3")

        assertEquals("cursor-3", readCursor(database, appId))
        assertEquals(2, countEvents(database, appId))
    }

    // --- The skipped_event row (design decision D5, issue #27) ---

    @Test
    fun `commitPage inserts each skipped event with its reason, in the same transaction as the cursor move`() = runBlocking {
        store.commitPage(
            appId,
            listOf(sampleEvent("e1")),
            cursor = "cursor-1",
            skippedEvents = listOf(SkippedEvent("bad-1", "ts missing"), SkippedEvent("bad-2", "userId empty")),
        )

        assertEquals(1, countEvents(database, appId))
        assertEquals("cursor-1", readCursor(database, appId))
        assertEquals(
            mapOf("bad-1" to "ts missing", "bad-2" to "userId empty"),
            readSkippedReasons(database, appId),
        )
    }

    @Test
    fun `commitPage with no skippedEvents argument keeps the old caller of issue 16 valid`() = runBlocking {
        store.commitPage(appId, listOf(sampleEvent("e1")), cursor = "cursor-1")

        assertEquals(1, countEvents(database, appId))
        assertEquals(0, readSkippedReasons(database, appId).size)
    }

    @Test
    fun `a replay with the same skipped events adds 0 new skipped_event rows`() = runBlocking {
        val skipped = listOf(SkippedEvent("bad-1", "ts missing"))
        store.commitPage(appId, emptyList(), cursor = "cursor-1", skippedEvents = skipped)

        store.commitPage(appId, emptyList(), cursor = "cursor-2", skippedEvents = skipped)

        assertEquals(1, readSkippedReasons(database, appId).size)
        assertEquals("cursor-2", readCursor(database, appId))
    }

    // BLOCKER 1 of correction round 1 (Kotlin backend engineer) extends
    // here. The failed page must roll back a skipped_event insert too,
    // not only an event insert.
    @Test
    fun `a failed insert rolls back a skipped_event row too`() = runBlocking {
        store.commitPage(appId, listOf(sampleEvent("e1")), cursor = "cursor-1")

        val badPage = listOf(sampleEvent("e2"), sampleEvent("e3", userId = ""))
        assertFailsWith<SQLException> {
            store.commitPage(appId, badPage, cursor = "cursor-2", skippedEvents = listOf(SkippedEvent("bad-1", "ts missing")))
        }

        assertEquals("cursor-1", readCursor(database, appId))
        assertEquals(0, readSkippedReasons(database, appId).size)
    }

    // --- recordCycleSuccess (design decisions D5, D8, issue #27) ---

    @Test
    fun `recordCycleSuccess sets last_poll_at, last_success_at, and the status`() = runBlocking {
        store.recordCycleSuccess(appId, nowMillis = 1_700_000_500_000L, status = "OK")

        val row = readAppRow(database, appId)
        assertEquals(1_700_000_500_000L, row.lastPollAt)
        assertEquals(1_700_000_500_000L, row.lastSuccessAt)
        assertEquals("OK", row.status)
    }

    @Test
    fun `recordCycleSuccess sets the status INVALID_DATA when the caller passes it`() = runBlocking {
        store.recordCycleSuccess(appId, nowMillis = 1_700_000_600_000L, status = "INVALID_DATA")

        assertEquals("INVALID_DATA", readAppRow(database, appId).status)
    }

    // The acceptance criterion of issue #28: a success after failures
    // gives consecutive_failures 0 and the status OK.
    @Test
    fun `recordCycleSuccess resets consecutive_failures to 0 and last_error to NULL`() = runBlocking {
        store.recordFailure(appId, status = "ERROR", lastError = "IllegalStateException", nextPollAt = 1_000L, nowMillis = 500L)
        store.recordFailure(appId, status = "ERROR", lastError = "IllegalStateException", nextPollAt = 2_000L, nowMillis = 1_000L)
        assertEquals(2, readAppRow(database, appId).consecutiveFailures, "two failed cycles in sequence, before the good cycle")

        store.recordCycleSuccess(appId, nowMillis = 1_700_000_700_000L, status = "OK")

        val row = readAppRow(database, appId)
        assertEquals("OK", row.status)
        assertEquals(0, row.consecutiveFailures, "a good cycle clears the failure count")
        assertEquals(null, row.lastError, "a good cycle clears the last error text")
    }

    // --- recordFailure (design decision D8, issue #28, the maintainer's decision 2) ---

    @Test
    fun `recordFailure increments consecutive_failures, sets last_poll_at, last_error, the status, and next_poll_at`() = runBlocking {
        store.recordFailure(appId, status = "UNAUTHORIZED", lastError = "13", nextPollAt = 5_000L, nowMillis = 1_000L)

        val row = readAppRow(database, appId)
        assertEquals("UNAUTHORIZED", row.status)
        assertEquals("13", row.lastError)
        assertEquals(1, row.consecutiveFailures)
        assertEquals(1_000L, row.lastPollAt)
        assertEquals(5_000L, row.nextPollAt)
        assertEquals(null, row.lastSuccessAt, "a failed cycle never sets last_success_at")
    }

    @Test
    fun `recordFailure never moves the cursor`() = runBlocking {
        store.commitPage(appId, listOf(sampleEvent("e1")), cursor = "cursor-1")

        store.recordFailure(appId, status = "ERROR", lastError = "IllegalStateException", nextPollAt = 5_000L, nowMillis = 1_000L)

        assertEquals("cursor-1", readCursor(database, appId), "recordFailure must never touch the cursor")
    }

    @Test
    fun `recordFailure increments consecutive_failures on each call`() = runBlocking {
        store.recordFailure(appId, status = "ERROR", lastError = "IllegalStateException", nextPollAt = 1_000L, nowMillis = 0L)
        store.recordFailure(appId, status = "UNREACHABLE", lastError = "MongoSocketException", nextPollAt = 3_000L, nowMillis = 1_000L)

        val row = readAppRow(database, appId)
        assertEquals(2, row.consecutiveFailures)
        assertEquals("UNREACHABLE", row.status)
        assertEquals("MongoSocketException", row.lastError)
    }

    // A defect of section 6 would let a NULL element pass. This test proves
    // the schema itself rejects it, not only the Kotlin type.
    @Test
    fun `a row with element NULL gives an error at the schema level`() = runBlocking {
        val error = assertFailsWith<SQLException> {
            database.write { writer ->
                writer.prepareStatement(
                    "INSERT INTO event (app_id, event_id, ts, element, session_id, user_id) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                ).use { insert ->
                    insert.setLong(1, appId)
                    insert.setString(2, "bad-event")
                    insert.setLong(3, 1L)
                    insert.setString(4, null)
                    insert.setString(5, "session-1")
                    insert.setString(6, null)
                    insert.executeUpdate()
                }
            }
        }
        assertTrue(error.message!!.contains("NOT NULL", ignoreCase = true))
    }

    private fun sampleEvent(eventId: String, userId: String? = "user-1") = NewEvent(
        eventId = eventId,
        ts = 1_700_000_000_000L,
        element = "checkout.save",
        sessionId = "session-1",
        userId = userId,
    )
}

private suspend fun insertApp(database: SqliteDatabase, name: String): Long =
    database.write { writer ->
        writer.prepareStatement(
            "INSERT INTO app (name, database_name, collection_name, created_at) VALUES (?, ?, ?, ?)",
        ).use { insert ->
            insert.setString(1, name)
            insert.setString(2, "db")
            insert.setString(3, "octometer_events")
            insert.setLong(4, 1_700_000_000_000L)
            insert.executeUpdate()
        }
        writer.createStatement().use { statement ->
            statement.executeQuery("SELECT last_insert_rowid()").use { result ->
                result.next()
                result.getLong(1)
            }
        }
    }

private suspend fun countEvents(database: SqliteDatabase, appId: Long): Int =
    database.read { reader ->
        reader.prepareStatement("SELECT COUNT(*) FROM event WHERE app_id = ?").use { select ->
            select.setLong(1, appId)
            select.executeQuery().use { result ->
                result.next()
                result.getInt(1)
            }
        }
    }

private suspend fun readCursor(database: SqliteDatabase, appId: Long): String? =
    database.read { reader ->
        reader.prepareStatement("SELECT cursor FROM app WHERE id = ?").use { select ->
            select.setLong(1, appId)
            select.executeQuery().use { result ->
                result.next()
                result.getString(1)
            }
        }
    }

/** Reads each `skipped_event` row of one app as a map of event id to reason (issue #27). */
private suspend fun readSkippedReasons(database: SqliteDatabase, appId: Long): Map<String, String> =
    database.read { reader ->
        reader.prepareStatement("SELECT event_id, reason FROM skipped_event WHERE app_id = ?").use { select ->
            select.setLong(1, appId)
            select.executeQuery().use { result ->
                val reasons = mutableMapOf<String, String>()
                while (result.next()) {
                    reasons[result.getString(1)] = result.getString(2)
                }
                reasons
            }
        }
    }

/**
 * The columns of one app row that a good or a failed poll cycle sets
 * (design decisions D5, D8, issue #27, issue #28).
 */
private data class AppRow(
    val status: String?,
    val lastPollAt: Long?,
    val lastSuccessAt: Long?,
    val lastError: String?,
    val consecutiveFailures: Int,
    val nextPollAt: Long?,
)

private suspend fun readAppRow(database: SqliteDatabase, appId: Long): AppRow =
    database.read { reader ->
        reader.prepareStatement(
            "SELECT status, last_poll_at, last_success_at, last_error, consecutive_failures, next_poll_at FROM app WHERE id = ?",
        ).use { select ->
            select.setLong(1, appId)
            select.executeQuery().use { result ->
                result.next()
                AppRow(
                    status = result.getString(1),
                    lastPollAt = result.getLong(2).takeUnless { result.wasNull() },
                    lastSuccessAt = result.getLong(3).takeUnless { result.wasNull() },
                    lastError = result.getString(4),
                    consecutiveFailures = result.getInt(5),
                    nextPollAt = result.getLong(6).takeUnless { result.wasNull() },
                )
            }
        }
    }
