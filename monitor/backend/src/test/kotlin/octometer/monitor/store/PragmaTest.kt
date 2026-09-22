package octometer.monitor.store

import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.SQLException
import kotlinx.coroutines.runBlocking
import octometer.monitor.registerTempRoot
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Step 4 of issue #9: each connection carries the same seven pragmas,
// before the migrations. One test checks the writer, and one test checks
// the reader, because the two are separate JDBC connections.
class PragmaTest {

    // SQLite MAJOR 1 of correction round 1: dataDir sits under root, so
    // the sibling backups folder of issue #55 stays inside root.
    private val root = Files.createTempDirectory("octometer-pragma-test-").toFile().also { registerTempRoot(it) }
    private val dataDir = File(root, "data")
    private val database = SqliteDatabase.open(dataDir.absolutePath)

    @AfterTest
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun `the writer connection has each of the seven pragmas`() = runBlocking {
        database.write { connection -> assertPragmas(connection) }
    }

    @Test
    fun `the reader connection has each of the seven pragmas`() = runBlocking {
        database.read { connection -> assertPragmas(connection) }
    }

    // MAJOR 1 of correction round 1 (SQLite and data engineer): the reader
    // connection must reject a write. A block body keeps the return type
    // Unit, because a JUnit test method must return no value.
    @Test
    fun `a write on the reader connection fails`() {
        runBlocking {
            assertFailsWith<SQLException> {
                database.read { connection ->
                    connection.createStatement().use {
                        it.execute("INSERT INTO gap (app_id, from_ts, to_ts) VALUES (1, 1, 2)")
                    }
                }
            }
        }
    }

    private fun assertPragmas(connection: Connection) {
        assertEquals("wal", readPragmaString(connection, "journal_mode"))
        assertEquals(1, readPragmaInt(connection, "synchronous"))
        assertEquals(5000, readPragmaInt(connection, "busy_timeout"))
        assertEquals(1, readPragmaInt(connection, "foreign_keys"))
        assertEquals(1, readPragmaInt(connection, "secure_delete"))
        assertEquals(2, readPragmaInt(connection, "temp_store"))
        assertEquals(-65536, readPragmaInt(connection, "cache_size"))
    }

    private fun readPragmaInt(connection: Connection, name: String): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA $name").use { result ->
                result.next()
                result.getInt(1)
            }
        }

    private fun readPragmaString(connection: Connection, name: String): String =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA $name").use { result ->
                result.next()
                result.getString(1)
            }
        }
}
