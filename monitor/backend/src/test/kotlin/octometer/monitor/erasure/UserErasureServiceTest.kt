package octometer.monitor.erasure

import java.io.File
import java.nio.file.Files
import java.sql.Connection
import kotlinx.coroutines.runBlocking
import octometer.monitor.store.SqliteDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Issue #61, steps 1 and 2. Rule E1: the erasure deletes the rows of the
// user, and the anonymous rows (user_id IS NULL) of each session that
// holds a row of that user. A row of a second user, and a row of a
// second app, stay. Rule E2: the function reads the session list of the
// user first, then it deletes.
class UserErasureServiceTest {

    private val tempDir = Files.createTempDirectory("octometer-user-erasure-test-").toFile()
    private lateinit var database: SqliteDatabase

    @BeforeTest
    fun setUp() = runBlocking {
        database = SqliteDatabase.open(tempDir.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        database.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun `the erasure removes the rows of the user and keeps a row of a second user in the same session`() = runBlocking {
        val appId = insertApp(database, "demo")
        insertEvent(database, appId, "e1", sessionId = "s1", userId = "user-a")
        insertEvent(database, appId, "e2", sessionId = "s1", userId = "user-b")

        val result = eraseUserEvents(database, appId, "user-a")

        assertEquals(1, result.total)
        assertEquals(listOf("user-b"), remainingUserIds(database, appId))
    }

    @Test
    fun `the erasure removes an anonymous row of a session that also holds a row of the user`() = runBlocking {
        val appId = insertApp(database, "demo")
        insertEvent(database, appId, "e1", sessionId = "s1", userId = null)
        insertEvent(database, appId, "e2", sessionId = "s1", userId = "user-a")

        val result = eraseUserEvents(database, appId, "user-a")

        assertEquals(2, result.total)
        assertEquals(0, countEvents(database, appId))
    }

    @Test
    fun `an anonymous row of a different session stays`() = runBlocking {
        val appId = insertApp(database, "demo")
        insertEvent(database, appId, "e1", sessionId = "s1", userId = "user-a")
        insertEvent(database, appId, "e2", sessionId = "s2", userId = null)

        val result = eraseUserEvents(database, appId, "user-a")

        assertEquals(1, result.total)
        assertEquals(1, countEvents(database, appId))
    }

    @Test
    fun `the erasure of one app leaves the same user id of a second app untouched`() = runBlocking {
        val appA = insertApp(database, "app-a")
        val appB = insertApp(database, "app-b")
        insertEvent(database, appA, "e1", sessionId = "s1", userId = "user-a")
        insertEvent(database, appB, "e1", sessionId = "s1", userId = "user-a")

        val result = eraseUserEvents(database, appA, "user-a")

        assertEquals(1, result.total)
        assertEquals(1, countEvents(database, appB))
    }

    @Test
    fun `a user with no events gives a deleted count of 0`() = runBlocking {
        val appId = insertApp(database, "demo")

        val result = eraseUserEvents(database, appId, "no-such-user")

        assertEquals(0, result.total)
    }

    @Test
    fun `the erasure matches a user id with special characters`() = runBlocking {
        val appId = insertApp(database, "demo")
        val userId = "user+plus & amp <tag> 'quote' 中文"
        insertEvent(database, appId, "e1", sessionId = "s1", userId = userId)

        val result = eraseUserEvents(database, appId, userId)

        assertEquals(1, result.total)
        assertEquals(0, countEvents(database, appId))
    }

    @Test
    fun `the erasure covers the session-start kind too`() = runBlocking {
        val appId = insertApp(database, "demo")
        insertEvent(database, appId, "e1", sessionId = "s1", userId = "user-a", kind = 1)
        insertEvent(database, appId, "e2", sessionId = "s1", userId = null, kind = 0)

        val result = eraseUserEvents(database, appId, "user-a")

        assertEquals(2, result.total)
    }

    @Test
    fun `the erasure runs a WAL checkpoint after the commit`() = runBlocking {
        val appId = insertApp(database, "demo")
        insertEvent(database, appId, "e1", sessionId = "s1", userId = "user-a")

        eraseUserEvents(database, appId, "user-a")

        // A TRUNCATE checkpoint moves each WAL frame into the main file, so
        // the WAL file goes back to an empty state.
        val walFile = File(tempDir, "octometer.db-wal")
        assertTrue(!walFile.exists() || walFile.length() == 0L, "expected an empty or an absent WAL file")
    }

    @Test
    fun `the session-id query uses the partial index of its kind`() = runBlocking {
        val appId = insertApp(database, "demo")
        insertEvent(database, appId, "e1", sessionId = "s1", userId = "user-a", kind = 0)
        insertEvent(database, appId, "e2", sessionId = "s1", userId = "user-a", kind = 1)

        val plan0 = database.read { queryPlan(it, SQL_SESSION_IDS_KIND0) }
        val plan1 = database.read { queryPlan(it, SQL_SESSION_IDS_KIND1) }

        assertTrue(plan0.contains("event_agg"), "expected the plan of the kind-0 query to name event_agg: $plan0")
        assertTrue(plan1.contains("event_start"), "expected the plan of the kind-1 query to name event_start: $plan1")
    }

    @Test
    fun `the delete statements use an index`() = runBlocking {
        val plan0 = database.read { queryPlan(it, SQL_DELETE_USER_KIND0) }
        val plan1 = database.read { queryPlan(it, SQL_DELETE_USER_KIND1) }
        val planAnon = database.read { queryPlan(it, SQL_DELETE_ANONYMOUS_OF_SESSION) }

        assertTrue(plan0.contains("event_agg"), "expected the kind-0 delete plan to name event_agg: $plan0")
        assertTrue(plan1.contains("event_start"), "expected the kind-1 delete plan to name event_start: $plan1")
        assertTrue(planAnon.contains("event_session"), "expected the anonymous delete plan to name event_session: $planAnon")
    }

    private fun queryPlan(connection: Connection, sql: String): String {
        val text = StringBuilder()
        connection.prepareStatement("EXPLAIN QUERY PLAN $sql").use { statement ->
            for (index in 1..sql.count { it == '?' }) {
                statement.setString(index, "x")
            }
            statement.executeQuery().use { result ->
                while (result.next()) {
                    text.append(result.getString("detail")).append('\n')
                }
            }
        }
        return text.toString()
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

private suspend fun insertEvent(
    database: SqliteDatabase,
    appId: Long,
    eventId: String,
    sessionId: String,
    userId: String?,
    kind: Int = 0,
) {
    database.write { writer ->
        writer.prepareStatement(
            "INSERT INTO event (app_id, event_id, ts, element, session_id, user_id, kind) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
        ).use { insert ->
            insert.setLong(1, appId)
            insert.setString(2, eventId)
            insert.setLong(3, 1_700_000_000_000L)
            insert.setString(4, "checkout.save")
            insert.setString(5, sessionId)
            insert.setString(6, userId)
            insert.setInt(7, kind)
            insert.executeUpdate()
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

private suspend fun remainingUserIds(database: SqliteDatabase, appId: Long): List<String> =
    database.read { reader ->
        reader.prepareStatement("SELECT user_id FROM event WHERE app_id = ? ORDER BY user_id").use { select ->
            select.setLong(1, appId)
            select.executeQuery().use { result ->
                val ids = mutableListOf<String>()
                while (result.next()) ids += result.getString(1)
                ids
            }
        }
    }
