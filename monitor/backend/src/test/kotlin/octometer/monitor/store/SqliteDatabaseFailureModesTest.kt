package octometer.monitor.store

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlinx.coroutines.runBlocking
import octometer.monitor.poll.sqliteFailureLastErrorText
import octometer.monitor.registerTempRoot
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Decisions 3 and 4 of issue #58: a real writer connection, against a
// real lock or a real full database, gives SQLiteException with the
// result code SQLITE_BUSY or SQLITE_FULL. PollSchedulerTest proves the
// mapping to last_error with a stub failure; this file proves the
// driver itself gives that result code, and that EventStore.commitPage
// rolls the page back and leaves the cursor as it was.
class SqliteDatabaseFailureModesTest {

    private val root = Files.createTempDirectory("octometer-failure-modes-test-").toFile().also { registerTempRoot(it) }
    private val dataDir = File(root, "data")
    private lateinit var database: SqliteDatabase
    private lateinit var store: EventStore
    private var appId: Long = 0

    @BeforeTest
    fun setUp() = runBlocking {
        database = SqliteDatabase.open(dataDir.absolutePath)
        store = EventStore(database)
        appId = database.write { writer ->
            writer.prepareStatement(
                "INSERT INTO app (name, database_name, collection_name, created_at) VALUES (?, ?, ?, ?)",
            ).use { insert ->
                insert.setString(1, "demo")
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
        store.commitPage(appId, listOf(sampleEvent("seed")), cursor = "cursor-0", expectedCursor = null)
        Unit
    }

    @AfterTest
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    // Decision 3: a small busy_timeout on the writer (200 ms, stated
    // here, well below the 5 000 ms default of D3) keeps this test
    // fast. A second connection holds BEGIN IMMEDIATE, the write lock,
    // for the whole page commit, so the writer waits the full 200 ms
    // and then gets SQLITE_BUSY.
    @Test
    fun `a page commit against a held write lock gives SQLITE_BUSY, and the cursor stays`() = runBlocking {
        database.write { writer -> writer.createStatement().use { it.execute("PRAGMA busy_timeout=200") } }
        val blockingUrl = "jdbc:sqlite:" + File(dataDir, "octometer.db").absolutePath
        val blockingConnection = DriverManager.getConnection(blockingUrl)
        blockingConnection.createStatement().use { it.execute("BEGIN IMMEDIATE") }

        try {
            val failure = assertFailsWith<SQLiteException> {
                store.commitPage(appId, listOf(sampleEvent("e1")), cursor = "cursor-1", expectedCursor = "cursor-0")
            }

            val primaryCode = failure.resultCode.code and 0xFF
            assertEquals(SQLiteErrorCode.SQLITE_BUSY.code, primaryCode, "the driver gives SQLITE_BUSY after the 200 ms wait")
            assertEquals(
                "the database was locked for more than 5 s",
                sqliteFailureLastErrorText(failure),
                "the shared mapping of PollScheduler gives the fixed busy text",
            )
        } finally {
            blockingConnection.createStatement().use { it.execute("ROLLBACK") }
            blockingConnection.close()
        }

        // The writer thread is free again; a second write, the same
        // form as PollScheduler.recordFailure, records the mapped
        // text and the status ERROR. The cursor stays at cursor-0: the
        // busy commit rolled the whole page back, and this write never
        // touches the cursor column.
        store.recordFailure(appId, status = "ERROR", lastError = "the database was locked for more than 5 s", nextPollAt = 1_000L, nowMillis = 500L)

        assertEquals("cursor-0", readCursor(appId), "a busy commit never moves the cursor")
        assertEquals(1, countEvents(appId), "only the seed event of setUp committed")
        assertEquals("ERROR", readStatus(appId))
    }

    // Decision 4: PRAGMA max_page_count simulates SQLITE_FULL, with no
    // real full disk. The page of 300 rows, each with a long path, grows
    // the file well past a small page count.
    @Test
    fun `a page commit against a small max_page_count gives SQLITE_FULL, and the cursor stays`() = runBlocking {
        val pageCountBefore = database.write { writer ->
            writer.createStatement().use { statement ->
                statement.executeQuery("PRAGMA page_count").use { result ->
                    result.next()
                    result.getInt(1)
                }
            }
        }
        database.write { writer ->
            writer.createStatement().use { it.execute("PRAGMA max_page_count=${pageCountBefore + 2}") }
        }

        val bigPage = (1..300).map { index -> sampleEvent("full-$index", path = "/checkout/".padEnd(120, 'x')) }
        val failure = assertFailsWith<SQLiteException> {
            store.commitPage(appId, bigPage, cursor = "cursor-full", expectedCursor = "cursor-0")
        }
        val primaryCode = failure.resultCode.code and 0xFF
        assertEquals(SQLiteErrorCode.SQLITE_FULL.code, primaryCode, "the driver gives SQLITE_FULL against the small max_page_count")
        assertEquals(
            "the database is full",
            sqliteFailureLastErrorText(failure),
            "the shared mapping of PollScheduler gives the fixed full text",
        )

        assertEquals("cursor-0", readCursor(appId), "the full page rolled back; the cursor stays")
        assertEquals(1, countEvents(appId), "the full page added 0 rows; only the seed event of setUp remains")

        // The maintainer's decision of the correction round: state
        // whether the failure update can still run on a full database.
        // An UPDATE of an existing row needs no new page in the common
        // case, so it still runs here, and the status becomes ERROR.
        store.recordFailure(appId, status = "ERROR", lastError = "the database is full", nextPollAt = 1_000L, nowMillis = 500L)
        assertEquals("ERROR", readStatus(appId), "the failure update still ran, against the same small max_page_count")
    }

    private fun sampleEvent(eventId: String, path: String? = null) = NewEvent(
        eventId = eventId,
        ts = 1_700_000_000_000L,
        element = "checkout.save",
        sessionId = "session-1",
        userId = "user-1",
        path = path,
    )

    private suspend fun readCursor(appId: Long): String? =
        database.read { reader ->
            reader.prepareStatement("SELECT cursor FROM app WHERE id = ?").use { select ->
                select.setLong(1, appId)
                select.executeQuery().use { result ->
                    result.next()
                    result.getString(1)
                }
            }
        }

    private suspend fun readStatus(appId: Long): String? =
        database.read { reader ->
            reader.prepareStatement("SELECT status FROM app WHERE id = ?").use { select ->
                select.setLong(1, appId)
                select.executeQuery().use { result ->
                    result.next()
                    result.getString(1)
                }
            }
        }

    private suspend fun countEvents(appId: Long): Int =
        database.read { reader ->
            reader.prepareStatement("SELECT COUNT(*) FROM event WHERE app_id = ?").use { select ->
                select.setLong(1, appId)
                select.executeQuery().use { result ->
                    result.next()
                    result.getInt(1)
                }
            }
        }
}
