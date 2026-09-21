package octometer.monitor.store

import java.nio.file.Files
import java.sql.Connection
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Step 4 of issue #9: each connection carries the same pragmas, before the
// migrations. One test opens the writer, and one test opens the reader,
// because the two are separate JDBC connections.
class PragmaTest {

    private val tempDir = Files.createTempDirectory("octometer-pragma-test-").toFile()
    private val database = SqliteDatabase.open(tempDir.absolutePath)

    @AfterTest
    fun tearDown() {
        database.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun `the writer connection has foreign_keys and secure_delete on`() {
        assertPragmasOn(database.writer)
    }

    @Test
    fun `the reader connection has foreign_keys and secure_delete on`() {
        assertPragmasOn(database.reader)
    }

    private fun assertPragmasOn(connection: Connection) {
        assertEquals(1, readPragma(connection, "foreign_keys"))
        assertEquals(1, readPragma(connection, "secure_delete"))
    }

    private fun readPragma(connection: Connection, name: String): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA $name").use { it.getInt(1) }
        }
}
