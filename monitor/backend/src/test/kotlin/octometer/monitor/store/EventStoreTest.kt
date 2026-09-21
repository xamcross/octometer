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
    fun setUp() = runBlocking {
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
