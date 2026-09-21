package octometer.monitor.store

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// MAJOR 3 of correction round 1 (Kotlin backend engineer): a failed open()
// must close each connection it already made, so the folder deletes
// cleanly. On Windows an open handle blocks the delete.
class SqliteDatabaseTest {

    @Test
    fun `a failed open closes each connection it already made`() {
        val tempDir = Files.createTempDirectory("octometer-open-failure-test-").toFile()
        try {
            val dbFile = File(tempDir, "octometer.db")
            DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
                connection.createStatement().use { it.execute("CREATE TABLE app (id INTEGER)") }
            }

            assertFailsWith<SQLException> {
                SqliteDatabase.open(tempDir.absolutePath)
            }

            // A locked file on Windows blocks this delete. The delete must
            // pass, thus each connection this call opened is closed.
            assertTrue(tempDir.deleteRecursively(), "the temp folder deletes with no locked file")
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
