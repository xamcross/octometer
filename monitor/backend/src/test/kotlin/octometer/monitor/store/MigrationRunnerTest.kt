package octometer.monitor.store

import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import octometer.monitor.backup.backupsDir

// Step 3 of issue #9: the migration runner uses PRAGMA user_version. The
// last acceptance criterion needs proof that a second start applies no
// migration, because the DDL of section 6 has no IF NOT EXISTS.
class MigrationRunnerTest {

    @Test
    fun `the migration sets PRAGMA user_version to the last applied version`() = runBlocking {
        val root = Files.createTempDirectory("octometer-migration-test-").toFile()
        try {
            val dataDir = File(root, "data").absolutePath
            val database = SqliteDatabase.open(dataDir)
            try {
                assertEquals(2, database.write { userVersion(it) })
            } finally {
                database.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a second start applies no migration`() = runBlocking {
        val root = Files.createTempDirectory("octometer-migration-test-").toFile()
        try {
            // A data folder under root, so the sibling backups folder of
            // issue #55 stays inside root and deletes with it.
            val dataDir = File(root, "data").absolutePath
            SqliteDatabase.open(dataDir).close()

            // A repeat CREATE TABLE without the PRAGMA user_version guard
            // throws here. The DDL of section 6 has no IF NOT EXISTS.
            val secondStart = SqliteDatabase.open(dataDir)
            try {
                assertEquals(2, secondStart.write { userVersion(it) })
                assertTrue(
                    secondStart.write { MigrationRunner.run(it, backupsDir(dataDir)) }.isEmpty(),
                )
            } finally {
                secondStart.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    // MAJOR 2 of correction round 1 (SQLite and data engineer): a database
    // that is newer than the code must stop with a clear error.
    @Test
    fun `a database that is newer than the code stops with a clear error`() {
        val root = Files.createTempDirectory("octometer-migration-test-").toFile()
        try {
            val dataDir = File(root, "data").apply { mkdirs() }
            val dbFile = File(dataDir, "octometer.db")
            DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
                connection.createStatement().use { it.execute("PRAGMA user_version = 99") }
            }

            val error = assertFailsWith<IllegalStateException> {
                SqliteDatabase.open(dataDir.absolutePath)
            }
            assertTrue(error.message!!.contains("99"), "the message names the found version")
        } finally {
            root.deleteRecursively()
        }
    }

    private fun userVersion(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { result ->
                result.next()
                result.getInt(1)
            }
        }
}
