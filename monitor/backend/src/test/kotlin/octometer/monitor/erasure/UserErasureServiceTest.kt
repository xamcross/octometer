package octometer.monitor.erasure

import ch.qos.logback.classic.Level
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import octometer.monitor.captureLogEvents
import octometer.monitor.registerTempRoot
import octometer.monitor.store.SqliteDatabase
import octometer.monitor.testDataDir
import kotlin.system.measureTimeMillis
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Issue #61, steps 1 and 2. Rule E1: the erasure deletes the rows of the
// user. It also deletes the anonymous rows (user_id IS NULL) of each
// session that holds a row of that user. A row of a second user, and a
// row of a second app, stay. Rule E2: the function reads the session
// list of the user first, then it deletes.
class UserErasureServiceTest {

    private val tempDir = Files.createTempDirectory("octometer-user-erasure-test-").toFile().also { registerTempRoot(it) }
    private lateinit var database: SqliteDatabase

    @BeforeTest
    fun setUp() = runBlocking {
        database = SqliteDatabase.open(testDataDir(tempDir))
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

    // MAJOR 3 (privacy review) and MAJOR 2 (SQL review) of #61 found a
    // gap. A row of the user with a kind other than 0 or 1 stayed.
    // This test failed before SQL_DELETE_USER_OTHER_KIND existed. It
    // was broken for a moment (the guard delete removed), it failed on
    // the count and on the remaining row, and it was then restored.
    @Test
    fun `the erasure removes a row of a kind other than 0 or 1, and counts it`() = runBlocking {
        val appId = insertApp(database, "demo")
        insertEvent(database, appId, "e1", sessionId = "s1", userId = "user-a", kind = 0)
        insertEvent(database, appId, "e2", sessionId = "s1", userId = "user-a", kind = 2)

        val result = eraseUserEvents(database, appId, "user-a")

        assertEquals(2, result.total)
        assertEquals(0, countEvents(database, appId))
    }

    // MINOR 8 (second SQL review of #61). A session whose only row of
    // the user has kind = 2 stayed out of the session list, so its
    // anonymous row kept the user id inside the session (contract rule
    // C43 covers each kind). This test failed before
    // SQL_SESSION_IDS_OTHER_KIND existed: it counted 1, not 2, and the
    // anonymous row stayed. It was restored, and the suite passed.
    @Test
    fun `the erasure removes an anonymous row of a session whose only user row has a kind other than 0 or 1`() =
        runBlocking {
            val appId = insertApp(database, "demo")
            insertEvent(database, appId, "e1", sessionId = "s1", userId = "user-a", kind = 2)
            insertEvent(database, appId, "e2", sessionId = "s1", userId = null, kind = 0)

            val result = eraseUserEvents(database, appId, "user-a")

            assertEquals(2, result.total)
            assertEquals(0, countEvents(database, appId))
        }

    @Test
    fun `a checkpoint with no other reader completes and reports checkpointed true`() = runBlocking {
        val appId = insertApp(database, "demo")
        insertEvent(database, appId, "e1", sessionId = "s1", userId = "user-a")

        val result = eraseUserEvents(database, appId, "user-a")

        assertTrue(result.checkpointed, "expected the checkpoint to complete with no other reader")
        // A TRUNCATE checkpoint moves each WAL frame into the main file, so
        // the WAL file goes back to an empty state.
        val walFile = File(File(tempDir, "data"), "octometer.db-wal")
        assertTrue(!walFile.exists() || walFile.length() == 0L, "expected an empty or an absent WAL file")
    }

    // BLOCKER 1 (privacy review) and MAJOR 1 (SQL review) of #61. A
    // second reader holds a read transaction open on the reader
    // connection of the same store, through database.read. The writer
    // thread then cannot truncate the WAL file.
    //
    // The old code was restored for a moment (call the pragma, read no
    // row, always report success). This test then failed on
    // `result.checkpointed`. The fix was restored, and the suite passed.
    @Test
    fun `a busy checkpoint retries, then reports checkpointed false with one warning`() = runBlocking {
        val appId = insertApp(database, "demo")
        insertEvent(database, appId, "e1", sessionId = "s1", userId = "user-a")

        val readerStarted = CountDownLatch(1)
        val releaseReader = CountDownLatch(1)
        val readerJob = launch(Dispatchers.IO) {
            database.read { reader ->
                reader.createStatement().use { statement ->
                    statement.executeQuery("SELECT 1 FROM app").use { it.next() }
                }
                readerStarted.countDown()
                releaseReader.await(30, TimeUnit.SECONDS)
            }
        }
        assertTrue(readerStarted.await(5, TimeUnit.SECONDS), "the reader did not start its transaction in time")

        val (result, events) = captureLogEvents { eraseUserEvents(database, appId, "user-a") }

        releaseReader.countDown()
        readerJob.join()

        assertFalse(result.checkpointed, "expected the busy checkpoint to report checkpointed = false")
        assertEquals(1, result.total)

        val warnings = events.filter { it.level == Level.WARN }
        assertEquals(1, warnings.size, "expected exactly one warning line")
        val warning = warnings.single().formattedMessage
        assertTrue(warning.contains(appId.toString()), "expected the warning to name the app id: $warning")
        assertFalse(warning.contains("user-a"), "the warning held the user id: $warning")
    }

    // MAJOR A (second SQL review of #61). The old form left
    // `busy_timeout=5000` in place, so five `TRUNCATE` tries held the
    // writer thread for about 28 seconds. `busy_timeout=0` on the
    // checkpoint block makes each try return at once.
    //
    // The old form was restored for a moment (the `busy_timeout=0` call
    // and the `PASSIVE` step removed, with five plain `TRUNCATE` tries
    // left). This test then failed at about 28 seconds. The fix was
    // restored, and the suite passed within the 2-second bound.
    @Test
    fun `the write block completes within 2 seconds with a busy reader`() = runBlocking {
        val appId = insertApp(database, "demo")
        insertEvent(database, appId, "e1", sessionId = "s1", userId = "user-a")

        val readerStarted = CountDownLatch(1)
        val releaseReader = CountDownLatch(1)
        val readerJob = launch(Dispatchers.IO) {
            database.read { reader ->
                reader.createStatement().use { statement ->
                    statement.executeQuery("SELECT 1 FROM app").use { it.next() }
                }
                readerStarted.countDown()
                releaseReader.await(30, TimeUnit.SECONDS)
            }
        }
        assertTrue(readerStarted.await(5, TimeUnit.SECONDS), "the reader did not start its transaction in time")

        val elapsedMillis = measureTimeMillis {
            eraseUserEvents(database, appId, "user-a")
        }

        releaseReader.countDown()
        readerJob.join()

        assertTrue(elapsedMillis < 2000, "expected the write block to finish within 2 seconds, took $elapsedMillis ms")
    }

    // MAJOR A (second SQL review of #61). A reader that opens its
    // snapshot only after the delete commits (the order of a user
    // interface poll) must not turn `checkpointed` false. `PASSIVE`
    // needs no reader to release its snapshot, unlike `TRUNCATE`.
    @Test
    fun `a reader on the newest snapshot still lets the checkpoint report checkpointed true`() = runBlocking {
        val appId = insertApp(database, "demo")
        insertEvent(database, appId, "e1", sessionId = "s1", userId = "user-a")

        database.write { writer ->
            writer.createStatement().use { it.execute("BEGIN IMMEDIATE") }
            writer.prepareStatement(SQL_DELETE_USER_KIND0).use { delete ->
                delete.setLong(1, appId)
                delete.setString(2, "user-a")
                delete.executeUpdate()
            }
            writer.createStatement().use { it.execute("COMMIT") }
        }

        val readerStarted = CountDownLatch(1)
        val releaseReader = CountDownLatch(1)
        val readerJob = launch(Dispatchers.IO) {
            database.read { reader ->
                reader.createStatement().use { statement ->
                    statement.executeQuery("SELECT 1 FROM app").use { it.next() }
                }
                readerStarted.countDown()
                releaseReader.await(30, TimeUnit.SECONDS)
            }
        }
        assertTrue(readerStarted.await(5, TimeUnit.SECONDS), "the reader did not start its transaction in time")

        val moved = database.write { writer -> checkpointAfterCommit(writer) }

        releaseReader.countDown()
        readerJob.join()

        assertTrue(moved, "expected PASSIVE to move every frame although a reader holds the newest snapshot")
    }

    @Test
    fun `the session-id query uses the partial index of its kind`() = runBlocking {
        val appId = insertApp(database, "demo")
        insertEvent(database, appId, "e1", sessionId = "s1", userId = "user-a", kind = 0)
        insertEvent(database, appId, "e2", sessionId = "s1", userId = "user-a", kind = 1)

        val plan0 = database.read { queryPlan(it, SQL_SESSION_IDS_KIND0) }
        val plan1 = database.read { queryPlan(it, SQL_SESSION_IDS_KIND1) }
        val planOther = database.read { queryPlan(it, SQL_SESSION_IDS_OTHER_KIND) }

        assertTrue(plan0.contains("event_agg"), "expected the plan of the kind-0 query to name event_agg: $plan0")
        assertTrue(plan1.contains("event_start"), "expected the plan of the kind-1 query to name event_start: $plan1")
        // event_session is not partial, so the plan carries no kind
        // literal; the same shape as the guard delete of SQL_DELETE_USER_OTHER_KIND.
        assertEquals(
            "SEARCH event USING COVERING INDEX event_session (app_id=?)\n",
            planOther,
            "expected the other-kind session query to scan event_session on app_id only",
        )
    }

    // SQL review MINOR 2: the previous form of this test could not fail
    // from a removed INDEXED BY clause. The planner already picks the
    // same index with no hint. Each plan text is now exact, so a later
    // change of the index shape shows here.
    @Test
    fun `the delete statements use an index`() = runBlocking {
        val plan0 = database.read { queryPlan(it, SQL_DELETE_USER_KIND0) }
        val plan1 = database.read { queryPlan(it, SQL_DELETE_USER_KIND1) }
        val planOther = database.read { queryPlan(it, SQL_DELETE_USER_OTHER_KIND) }
        val planAnon = database.read { queryPlan(it, SQL_DELETE_ANONYMOUS_OF_SESSION) }

        assertEquals("SEARCH event USING COVERING INDEX event_agg (app_id=? AND user_id=?)\n", plan0)
        assertEquals("SEARCH event USING COVERING INDEX event_start (app_id=? AND user_id=?)\n", plan1)
        assertEquals("SEARCH event USING COVERING INDEX event_session (app_id=?)\n", planOther)
        assertEquals("SEARCH event USING COVERING INDEX event_session (app_id=? AND session_id=?)\n", planAnon)
    }

    // SQL review, "keep INDEXED BY": a schema change that drops or
    // renames one of these three indexes must fail the build. It must
    // not fail the erasure at run time. This test names each index.
    @Test
    fun `the three indexes of the erasure exist in sqlite_master`() = runBlocking {
        val names = database.read { connection ->
            val found = mutableSetOf<String>()
            connection.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND name IN (?, ?, ?)",
            ).use { select ->
                select.setString(1, "event_agg")
                select.setString(2, "event_start")
                select.setString(3, "event_session")
                select.executeQuery().use { result ->
                    while (result.next()) {
                        found += result.getString(1)
                    }
                }
            }
            found
        }

        assertEquals(setOf("event_agg", "event_start", "event_session"), names)
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
