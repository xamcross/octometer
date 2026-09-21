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

// Step 3 of issue #9: the migration runner uses PRAGMA user_version. The
// last acceptance criterion needs proof that a second start applies no
// migration, because the DDL of section 6 has no IF NOT EXISTS.
class MigrationRunnerTest {

    @Test
    fun `the migration sets PRAGMA user_version to the last applied version`() = runBlocking {
        val tempDir = Files.createTempDirectory("octometer-migration-test-").toFile()
        try {
            val database = SqliteDatabase.open(tempDir.absolutePath)
            try {
                assertEquals(2, database.write { userVersion(it) })
            } finally {
                database.close()
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `a second start applies no migration`() = runBlocking {
        val tempDir = Files.createTempDirectory("octometer-migration-test-").toFile()
        try {
            SqliteDatabase.open(tempDir.absolutePath).close()

            // A repeat CREATE TABLE without the PRAGMA user_version guard
            // throws here. The DDL of section 6 has no IF NOT EXISTS.
            val secondStart = SqliteDatabase.open(tempDir.absolutePath)
            try {
                assertEquals(2, secondStart.write { userVersion(it) })
                assertTrue(secondStart.write { MigrationRunner.run(it) }.isEmpty())
            } finally {
                secondStart.close()
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // MAJOR 2 of correction round 1 (SQLite and data engineer): a database
    // that is newer than the code must stop with a clear error.
    @Test
    fun `a database that is newer than the code stops with a clear error`() {
        val tempDir = Files.createTempDirectory("octometer-migration-test-").toFile()
        try {
            val dbFile = File(tempDir, "octometer.db")
            DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
                connection.createStatement().use { it.execute("PRAGMA user_version = 99") }
            }

            val error = assertFailsWith<IllegalStateException> {
                SqliteDatabase.open(tempDir.absolutePath)
            }
            assertTrue(error.message!!.contains("99"), "the message names the found version")
        } finally {
            tempDir.deleteRecursively()
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
