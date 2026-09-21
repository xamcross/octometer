package octometer.monitor.store

import java.nio.file.Files
import java.sql.SQLException
import kotlinx.coroutines.runBlocking
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

    private val tempDir = Files.createTempDirectory("octometer-event-store-test-").toFile()
    private lateinit var database: SqliteDatabase
    private lateinit var store: EventStore
    private var appId: Long = 0

    @BeforeTest
    fun setUp() {
        database = SqliteDatabase.open(tempDir.absolutePath)
        store = EventStore(database)
        appId = insertApp(database, "demo")
    }

    @AfterTest
    fun tearDown() {
        database.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun `commitPage inserts each event and moves the cursor`() = runBlocking {
        store.commitPage(appId, listOf(sampleEvent("e1")), cursor = "cursor-1")

        assertEquals(1, countEvents(database, appId))
        assertEquals("cursor-1", readCursor(database, appId))
    }

    @Test
    fun `a replay of the same page adds 0 rows`() = runBlocking {
        val page = listOf(sampleEvent("e1"), sampleEvent("e2"))
        store.commitPage(appId, page, cursor = "cursor-1")

        store.commitPage(appId, page, cursor = "cursor-2")

        assertEquals(2, countEvents(database, appId))
    }

    // The first acceptance criterion of issue #9: a failed insert leaves the
    // cursor as it was. The empty user id breaks the CHECK constraint of
    // section 6, so the whole page rolls back inside BEGIN IMMEDIATE.
    @Test
    fun `a failed insert leaves the cursor as it was`() = runBlocking {
        store.commitPage(appId, listOf(sampleEvent("e1")), cursor = "cursor-1")

        val badPage = listOf(sampleEvent("e2", userId = ""))
        assertFailsWith<SQLException> {
            store.commitPage(appId, badPage, cursor = "cursor-2")
        }

        assertEquals("cursor-1", readCursor(database, appId))
        assertEquals(1, countEvents(database, appId))
    }

    // A defect of section 6 would let a NULL element pass. This test proves
    // the schema itself rejects it, not only the Kotlin type.
    @Test
    fun `a row with element NULL gives an error at the schema level`() {
        val error = assertFailsWith<SQLException> {
            database.writer.prepareStatement(
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

private fun insertApp(database: SqliteDatabase, name: String): Long {
    database.writer.prepareStatement(
        "INSERT INTO app (name, database_name, collection_name, created_at) VALUES (?, ?, ?, ?)",
    ).use { insert ->
        insert.setString(1, name)
        insert.setString(2, "db")
        insert.setString(3, "octometer_events")
        insert.setLong(4, 1_700_000_000_000L)
        insert.executeUpdate()
    }
    database.writer.createStatement().use { statement ->
        statement.executeQuery("SELECT last_insert_rowid()").use { result ->
            result.next()
            return result.getLong(1)
        }
    }
}

private fun countEvents(database: SqliteDatabase, appId: Long): Int {
    database.reader.prepareStatement("SELECT COUNT(*) FROM event WHERE app_id = ?").use { select ->
        select.setLong(1, appId)
        select.executeQuery().use { result ->
            result.next()
            return result.getInt(1)
        }
    }
}

private fun readCursor(database: SqliteDatabase, appId: Long): String? {
    database.reader.prepareStatement("SELECT cursor FROM app WHERE id = ?").use { select ->
        select.setLong(1, appId)
        select.executeQuery().use { result ->
            result.next()
            return result.getString(1)
        }
    }
}
