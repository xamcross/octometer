package octometer.monitor.store

import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Issue #226: migration 004 adds the column path_patterns to app, the
// route pattern list of one app (design decision D5 of 2026-09-27, the
// per-app route pattern allow-list).
class Migration004Test {

    private val tempDir = Files.createTempDirectory("octometer-migration-004-test-").toFile()
    private val dataDir = File(tempDir, "data")

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `a new database gets user_version 8 and the column path_patterns`() = runBlocking {
        val database = SqliteDatabase.open(dataDir.absolutePath)
        try {
            assertEquals(8, database.write { userVersion(it) })
            assertTrue(database.write { appColumns(it) }.contains("path_patterns"), "the column path_patterns exists")
        } finally {
            database.close()
        }
    }

    @Test
    fun `an app row from before this migration gets a NULL path_patterns value`() = runBlocking {
        val dbFile = File(dataDir.apply { mkdirs() }, "octometer.db")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            createV3Schema(connection)
            connection.createStatement().use { it.execute("PRAGMA user_version = 3") }
            insertApp(connection, "demo")
        }

        val database = SqliteDatabase.open(dataDir.absolutePath)
        try {
            assertEquals(8, database.write { userVersion(it) })
            assertNull(database.read { pathPatternsOf(it, "demo") })
        } finally {
            database.close()
        }
    }

    private fun userVersion(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { result ->
                result.next()
                result.getInt(1)
            }
        }

    private fun appColumns(connection: Connection): Set<String> {
        val columns = mutableSetOf<String>()
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(app)").use { result ->
                while (result.next()) {
                    columns += result.getString("name")
                }
            }
        }
        return columns
    }

    private fun pathPatternsOf(connection: Connection, name: String): String? =
        connection.prepareStatement("SELECT path_patterns FROM app WHERE name = ?").use { select ->
            select.setString(1, name)
            select.executeQuery().use { result ->
                result.next()
                result.getString(1)
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

    // The schema of migration 003, copied here so this test can build a
    // database that is at user_version 3, with no migration 004 applied yet.
    private fun createV3Schema(connection: Connection) {
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
                  path TEXT, referrer_host TEXT, kind INTEGER NOT NULL DEFAULT 0,
                  PRIMARY KEY (app_id, event_id)
                ) STRICT;

                CREATE INDEX event_agg ON event(app_id, user_id, element, session_id, ts) WHERE kind = 0;
                CREATE INDEX event_session ON event(app_id, session_id);
                CREATE INDEX event_first_page ON event(app_id, session_id, ts) WHERE kind = 1;
                CREATE INDEX event_start ON event(app_id, user_id, ts) WHERE kind = 1;

                CREATE TABLE skipped_event (app_id INTEGER NOT NULL, event_id TEXT NOT NULL, reason TEXT NOT NULL,
                  PRIMARY KEY (app_id, event_id)) STRICT;
                CREATE TABLE gap (app_id INTEGER NOT NULL, from_ts INTEGER NOT NULL, to_ts INTEGER NOT NULL) STRICT;

                CREATE TABLE app_path (
                  app_id INTEGER NOT NULL REFERENCES app(id) ON DELETE CASCADE,
                  path TEXT NOT NULL,
                  first_seen_at INTEGER NOT NULL,
                  PRIMARY KEY (app_id, path)
                ) STRICT;
                """.trimIndent(),
            )
        }
    }
}
