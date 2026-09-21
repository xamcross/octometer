package octometer.monitor.store

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Step 3 of issue #9: the migration runner uses PRAGMA user_version. The
// last acceptance criterion needs proof that a second start applies no
// migration, because the DDL of section 6 has no IF NOT EXISTS.
class MigrationRunnerTest {

    @Test
    fun `the migration sets PRAGMA user_version to the last applied version`() {
        val tempDir = Files.createTempDirectory("octometer-migration-test-").toFile()
        try {
            val database = SqliteDatabase.open(tempDir.absolutePath)
            try {
                assertEquals(1, userVersion(database))
            } finally {
                database.close()
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `a second start applies no migration`() {
        val tempDir = Files.createTempDirectory("octometer-migration-test-").toFile()
        try {
            SqliteDatabase.open(tempDir.absolutePath).close()

            // A repeat CREATE TABLE without the PRAGMA user_version guard
            // throws here, because the DDL has no IF NOT EXISTS.
            val secondStart = SqliteDatabase.open(tempDir.absolutePath)
            try {
                assertEquals(1, userVersion(secondStart))
                assertTrue(MigrationRunner.run(secondStart.writer).isEmpty())
            } finally {
                secondStart.close()
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun userVersion(database: SqliteDatabase): Int =
        database.writer.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { it.getInt(1) }
        }
}
