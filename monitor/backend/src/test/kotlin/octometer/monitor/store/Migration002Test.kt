package octometer.monitor.store

import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import octometer.monitor.backup.backupsDir

// Issue #109: migration 002 adds the first-page columns and rebuilds the
// indexes as partial indexes. Source: the design brief of #101, rules M1
// and M2.
class Migration002Test {

    private val tempDir = Files.createTempDirectory("octometer-migration-002-test-").toFile()

    // The data folder sits under tempDir, so the sibling backups folder of
    // issue #55 stays inside tempDir and deletes with it.
    private val dataDir = File(tempDir, "data")

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `a new database gets user_version 2 and the three columns`() = runBlocking {
        val database = SqliteDatabase.open(dataDir.absolutePath)
        try {
            assertEquals(2, database.write { userVersion(it) })
            val columns = database.write { tableColumns(it) }
            assertTrue(columns.contains("path"), "the column path exists")
            assertTrue(columns.contains("referrer_host"), "the column referrer_host exists")
            assertTrue(columns.contains("kind"), "the column kind exists")
        } finally {
            database.close()
        }
    }

    @Test
    fun `a database at user_version 1 with rows gets migration 002 and keeps its rows`() = runBlocking {
        val dbFile = File(dataDir.apply { mkdirs() }, "octometer.db")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            createV1Schema(connection)
            connection.createStatement().use { it.execute("PRAGMA user_version = 1") }
            insertApp(connection, "demo")
            insertV1Event(connection, appId = 1L, eventId = "e1")
        }

        val database = SqliteDatabase.open(dataDir.absolutePath)
        try {
            assertEquals(2, database.write { userVersion(it) })
            assertEquals(1, database.read { countEvents(it) })
        } finally {
            database.close()
        }
    }

    @Test
    fun `the four indexes exist with the WHERE text of the criteria`() = runBlocking {
        val database = SqliteDatabase.open(dataDir.absolutePath)
        try {
            assertTrue(
                indexSql(database, "event_agg")!!.contains("WHERE kind = 0"),
                "event_agg carries the click filter",
            )
            assertTrue(
                indexSql(database, "event_first_page")!!.contains("WHERE kind = 1"),
                "event_first_page carries the session-start filter",
            )
            assertTrue(
                indexSql(database, "event_start")!!.contains("WHERE kind = 1"),
                "event_start carries the session-start filter",
            )
            assertTrue(indexSql(database, "event_session") != null, "event_session exists")
        } finally {
            database.close()
        }
    }

    @Test
    fun `a second start applies no migration`() = runBlocking {
        SqliteDatabase.open(dataDir.absolutePath).close()

        val secondStart = SqliteDatabase.open(dataDir.absolutePath)
        try {
            assertEquals(2, secondStart.write { userVersion(it) })
            assertTrue(secondStart.write { MigrationRunner.run(it, backupsDir(dataDir.absolutePath)) }.isEmpty())
        } finally {
            secondStart.close()
        }
    }

    // Rule of #109: an insert without kind must still store the value 0,
    // because #110 fills the new columns, not this issue.
    @Test
    fun `an insert without kind stores the value 0`() = runBlocking {
        val database = SqliteDatabase.open(dataDir.absolutePath)
        try {
            val store = EventStore(database)
            val appId = insertApp(database, "demo")
            store.commitPage(
                appId,
                listOf(
                    NewEvent(
                        eventId = "e1",
                        ts = 1_700_000_000_000L,
                        element = "checkout.save",
                        sessionId = "session-1",
                        userId = "user-1",
                    ),
                ),
                cursor = "cursor-1",
            )

            assertEquals(0, database.read { kindOf(it, appId, "e1") })
        } finally {
            database.close()
        }
    }

    @Test
    fun `the guard against a newer database now names version 2`() {
        val dbFile = File(dataDir.apply { mkdirs() }, "octometer.db")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            connection.createStatement().use { it.execute("PRAGMA user_version = 99") }
        }

        val error = assertFailsWith<IllegalStateException> {
            SqliteDatabase.open(dataDir.absolutePath)
        }
        assertTrue(error.message!!.contains("this build knows 2"), "the message names the latest version 2")
    }

    private fun userVersion(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { result ->
                result.next()
                result.getInt(1)
            }
        }

    private fun tableColumns(connection: Connection): Set<String> {
        val columns = mutableSetOf<String>()
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(event)").use { result ->
                while (result.next()) {
                    columns += result.getString("name")
                }
            }
        }
        return columns
    }

    private suspend fun indexSql(database: SqliteDatabase, name: String): String? =
        database.read { connection ->
            connection.prepareStatement("SELECT sql FROM sqlite_master WHERE name = ?").use { select ->
                select.setString(1, name)
                select.executeQuery().use { result ->
                    if (result.next()) result.getString(1) else null
                }
            }
        }

    private fun countEvents(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM event").use { result ->
                result.next()
                result.getInt(1)
            }
        }

    private fun kindOf(connection: Connection, appId: Long, eventId: String): Int =
        connection.prepareStatement("SELECT kind FROM event WHERE app_id = ? AND event_id = ?").use { select ->
            select.setLong(1, appId)
            select.setString(2, eventId)
            select.executeQuery().use { result ->
                result.next()
                result.getInt(1)
            }
        }

    // The schema of 001_init.sql, copied here so this test can build a
    // database that is at user_version 1, with no migration 002 applied yet.
    private fun createV1Schema(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE app (
                  id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, database_name TEXT NOT NULL,
                  collection_name TEXT NOT NULL, created_at INTEGER NOT NULL,
                  cursor TEXT, next_poll_at INTEGER, last_poll_at INTEGER, last_success_at INTEGER,
                  status TEXT, last_error TEXT, consecutive_failures INTEGER NOT NULL DEFAULT 0,
                  privileges_checked_at INTEGER
                ) STRICT;

                CREATE TABLE event (
                  app_id INTEGER NOT NULL REFERENCES app(id), event_id TEXT NOT NULL,
                  ts INTEGER NOT NULL,
                  element TEXT NOT NULL, session_id TEXT NOT NULL,
                  user_id TEXT CHECK (user_id IS NULL OR user_id <> ''),
                  PRIMARY KEY (app_id, event_id)
                ) STRICT;

                CREATE INDEX event_agg     ON event(app_id, user_id, element, session_id, ts);
                CREATE INDEX event_session ON event(app_id, session_id);

                CREATE TABLE skipped_event (app_id INTEGER NOT NULL, event_id TEXT NOT NULL, reason TEXT NOT NULL,
                  PRIMARY KEY (app_id, event_id)) STRICT;
                CREATE TABLE gap (app_id INTEGER NOT NULL, from_ts INTEGER NOT NULL, to_ts INTEGER NOT NULL) STRICT;
                """.trimIndent(),
            )
        }
    }

    private fun insertApp(connection: Connection, name: String) {
        connection.prepareStatement(
            "INSERT INTO app (name, database_name, collection_name, created_at) VALUES (?, ?, ?, ?)",
        ).use { insert ->
            insert.setString(1, name)
            insert.setString(2, "db")
            insert.setString(3, "octometer_events")
            insert.setLong(4, 1_700_000_000_000L)
            insert.executeUpdate()
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

    private fun insertV1Event(connection: Connection, appId: Long, eventId: String) {
        connection.prepareStatement(
            "INSERT INTO event (app_id, event_id, ts, element, session_id, user_id) VALUES (?, ?, ?, ?, ?, ?)",
        ).use { insert ->
            insert.setLong(1, appId)
            insert.setString(2, eventId)
            insert.setLong(3, 1_700_000_000_000L)
            insert.setString(4, "checkout.save")
            insert.setString(5, "session-1")
            insert.setString(6, "user-1")
            insert.executeUpdate()
        }
    }
}
