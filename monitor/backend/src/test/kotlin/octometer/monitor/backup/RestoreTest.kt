package octometer.monitor.backup

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import octometer.monitor.registerTempRoot
import octometer.monitor.store.RestoredDatabaseNeedsCleanupException
import octometer.monitor.store.SqliteDatabase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// BLOCKER 1 of correction round 1 for issue #55 (the reliability review):
// a restore must give the old data back, following the steps of
// monitor/backend/README.md. This test runs those steps against a real
// folder, with a real SqliteDatabase on each side.
class RestoreTest {

    private val root = Files.createTempDirectory("octometer-restore-test-").toFile().also { registerTempRoot(it) }
    private val clock: Clock = Clock.fixed(Instant.parse("2026-01-08T09:00:00Z"), ZoneOffset.UTC)

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `a restore that follows every step of the README gives back the row count of the backup`() = runBlocking {
        val dataDir = File(root, "data")
        val backupsDir = File(root, "backups")

        // Step 1: the monitor runs, and writes 3 rows, then one backup.
        val backupFileName = "octometer-restore-test.db"
        val database = SqliteDatabase.open(dataDir.absolutePath, backupDir = backupsDir.absolutePath, clock = clock)
        try {
            repeat(3) { insertApp(database, "app-$it") }
            database.write { connection -> DatabaseBackup.writeTo(connection, backupsDir, backupFileName) }

            // 2 more rows land after the backup. A real restore must lose
            // them, because they are not inside the backup file.
            repeat(2) { insertApp(database, "later-app-$it") }
            assertEquals(5, database.read { countApps(it) }, "5 rows exist right before the stop")
        } finally {
            // README step 1: stop the monitor.
            database.close()
        }

        // README step 2: copy the backup file over data\octometer.db.
        val backupFile = File(backupsDir, backupFileName)
        val liveDatabaseFile = File(dataDir, "octometer.db")
        Files.copy(backupFile.toPath(), liveDatabaseFile.toPath(), StandardCopyOption.REPLACE_EXISTING)

        // README step 3: delete octometer.db-wal and octometer.db-shm.
        // This step is not optional; the guard test below proves why.
        File(dataDir, "octometer.db-wal").delete()
        File(dataDir, "octometer.db-shm").delete()

        // README step 4: start the monitor.
        val restored = SqliteDatabase.open(dataDir.absolutePath, backupDir = backupsDir.absolutePath, clock = clock)
        try {
            // README step 5: compare the event count with the backup.
            assertEquals(3, restored.read { countApps(it) }, "the restore gives back the row count of the backup")
        } finally {
            restored.close()
        }
    }

    // BLOCKER 1, part (b), the second case: a restore that skips README
    // step 3 must never open in silence. This keeps octometer.db-wal with
    // real content beside the restored, rollback-journal backup file, and
    // asserts that SqliteDatabase.open refuses the start.
    @Test
    fun `a restore that keeps a stale write-ahead log file refuses the start`() = runBlocking {
        val dataDir = File(root, "data")
        val backupsDir = File(root, "backups")

        val backupFileName = "octometer-restore-guard-test.db"
        val database = SqliteDatabase.open(dataDir.absolutePath, backupDir = backupsDir.absolutePath, clock = clock)
        try {
            insertApp(database, "app-0")
            database.write { connection -> DatabaseBackup.writeTo(connection, backupsDir, backupFileName) }
        } finally {
            database.close()
        }

        val backupFile = File(backupsDir, backupFileName)
        val liveDatabaseFile = File(dataDir, "octometer.db")
        Files.copy(backupFile.toPath(), liveDatabaseFile.toPath(), StandardCopyOption.REPLACE_EXISTING)

        // A stale write-ahead log file with real content, kept in place
        // instead of deleted. A file with this name can only belong to a
        // WAL-mode database, never to the rollback-journal file that a
        // backup always is, thus its mere presence with content proves a
        // skipped README step 3.
        File(dataDir, "octometer.db-wal").writeBytes(ByteArray(64) { 7 })

        val error = assertFailsWith<RestoredDatabaseNeedsCleanupException> {
            SqliteDatabase.open(dataDir.absolutePath, backupDir = backupsDir.absolutePath, clock = clock)
        }
        assertTrue(error.message!!.contains("octometer.db-wal"), "the message names the file to delete")
        // The code never deletes the side file itself.
        assertTrue(File(dataDir, "octometer.db-wal").isFile, "the stale write-ahead log file still exists")
    }

    private suspend fun insertApp(database: SqliteDatabase, name: String) {
        database.write { writer -> insertApp(writer, name) }
    }

    private fun insertApp(writer: Connection, name: String) {
        writer.prepareStatement(
            "INSERT INTO app (name, database_name, collection_name, created_at) VALUES (?, ?, ?, ?)",
        ).use { insert ->
            insert.setString(1, name)
            insert.setString(2, "db")
            insert.setString(3, "octometer_events")
            insert.setLong(4, 1_700_000_000_000L)
            insert.executeUpdate()
        }
    }

    private fun countApps(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM app").use { result ->
                result.next()
                result.getInt(1)
            }
        }
}
