package octometer.monitor.store

import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import octometer.monitor.backup.backupsDir
import octometer.monitor.registerTempRoot

// Step 4 of issue #55: the migration runner backs up the database before
// it applies a pending migration. Source: D36 and the acceptance criteria
// of issue #55.
class MigrationRunnerBackupTest {

    private val root =
        Files.createTempDirectory("octometer-migration-backup-test-").toFile().also { registerTempRoot(it) }
    private val dataDir = File(root, "data").absolutePath
    private val clock: Clock = Clock.fixed(Instant.parse("2026-01-08T09:15:30Z"), ZoneOffset.UTC)

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `a new database with no pending migration writes no pre-migration backup`() {
        // The first open of a fresh folder runs every migration once, so
        // this checks the second open, where user_version already equals
        // the latest version and no migration is pending.
        SqliteDatabase.open(dataDir).close()
        val filesAfterFirstOpen = backupsDir(dataDir).listFiles()?.toList() ?: emptyList()

        SqliteDatabase.open(dataDir, clock = clock).close()

        val filesAfterSecondOpen = backupsDir(dataDir).listFiles()?.toList() ?: emptyList()
        assertEquals(filesAfterFirstOpen.map { it.name }.toSet(), filesAfterSecondOpen.map { it.name }.toSet())
    }

    @Test
    fun `a database at user_version 1 gets a pre-migrate backup before migration 2 runs`() {
        val dbFile = File(File(dataDir).apply { mkdirs() }, "octometer.db")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            createV1Schema(connection)
            connection.createStatement().use { it.execute("PRAGMA user_version = 1") }
        }

        val database = SqliteDatabase.open(dataDir, clock = clock)
        try {
            val backups = backupsDir(dataDir).listFiles()?.map { it.name } ?: emptyList()
            val preMigrateFile = backups.singleOrNull { it.startsWith("pre-migrate-v1-") }
            assertTrue(preMigrateFile != null, "a pre-migrate-v1 backup file exists")
            checkNotNull(preMigrateFile)

            // The backup was made before the migration touched the schema,
            // thus it opens at user_version 1, with no event table yet.
            val backupPath = File(backupsDir(dataDir), preMigrateFile).absolutePath
            DriverManager.getConnection("jdbc:sqlite:$backupPath").use { restored ->
                val version = restored.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA user_version").use { result ->
                        result.next()
                        result.getInt(1)
                    }
                }
                assertEquals(1, version, "the backup shows the schema before the migration")
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `a failed backup stops the migration and the start, and leaves the database file at its old version`() {
        val dbFile = File(File(dataDir).apply { mkdirs() }, "octometer.db")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            createV1Schema(connection)
            connection.createStatement().use { it.execute("PRAGMA user_version = 1") }
        }

        // A plain file in place of the backups folder makes VACUUM INTO
        // fail: SQLite cannot create the temporary file inside it.
        val backups = backupsDir(dataDir)
        backups.parentFile.mkdirs()
        backups.writeText("not a folder")

        assertFailsWith<Exception> {
            SqliteDatabase.open(dataDir, clock = clock)
        }

        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            val version = connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { result ->
                    result.next()
                    result.getInt(1)
                }
            }
            assertEquals(1, version, "the failed backup left the database file at its old version")
        }
    }

    // The schema of 001_init.sql, copied here so this test can build a
    // database at user_version 1, with the event table that migration 002
    // needs (its ALTER TABLE statements target that table).
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
}
