package octometer.monitor.store

import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// MAJOR 3 of correction round 1 (Kotlin backend engineer): a failed open()
// must close each connection it already made, so the folder deletes
// cleanly. On Windows an open handle blocks the delete.
class SqliteDatabaseTest {

    @Test
    fun `a failed open closes each connection it already made`() {
        val root = Files.createTempDirectory("octometer-open-failure-test-").toFile()
        try {
            // The data folder sits under root, so the sibling backups
            // folder of issue #55 stays inside root and deletes with it.
            val dataDir = File(root, "data").apply { mkdirs() }
            val dbFile = File(dataDir, "octometer.db")
            DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
                connection.createStatement().use { it.execute("CREATE TABLE app (id INTEGER)") }
            }

            assertFailsWith<SQLException> {
                SqliteDatabase.open(dataDir.absolutePath)
            }

            // A locked file on Windows blocks this delete. The delete must
            // pass, thus each connection this call opened is closed.
            assertTrue(root.deleteRecursively(), "the temp folder deletes with no locked file")
        } finally {
            root.deleteRecursively()
        }
    }
}

// MAJOR 1 of correction round 1 for pull request #127 (Kotlin backend and
// SQL engineer): `read {}` must give one snapshot for each statement of
// its block, so a write of the poll loop between two statements of one
// request never gives an impossible row.
class SqliteDatabaseReadTransactionTest {

    private val tempDir = Files.createTempDirectory("octometer-read-transaction-test-").toFile()
    private lateinit var database: SqliteDatabase

    @BeforeTest
    fun setUp() = runBlocking {
        database = SqliteDatabase.open(tempDir.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        database.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun `two statements of one read block see the same count, though a write commits between them`() =
        runBlocking {
            insertApp(database, "app-1")

            val (before, after) = database.read { reader ->
                val firstCount = countApps(reader)
                // The probe of the review: the writer commits a row while
                // this block still runs on the reader connection.
                runBlocking { database.write { writer -> insertApp(writer, "app-2") } }
                val secondCount = countApps(reader)
                firstCount to secondCount
            }

            assertEquals(before, after, "one read block gives one snapshot to each statement")
        }

    @Test
    fun `a read block that throws still lets the next read block run`() = runBlocking {
        assertFailsWith<IllegalStateException> {
            database.read { error("a defect inside the block") }
        }

        // A rollback that failed to run would leave the reader connection
        // inside a transaction, and the next BEGIN would throw.
        val count = database.read { reader -> countApps(reader) }

        assertEquals(0, count)
    }
}

private suspend fun insertApp(database: SqliteDatabase, name: String): Long =
    database.write { writer -> insertApp(writer, name) }

private fun insertApp(writer: Connection, name: String): Long {
    writer.prepareStatement(
        "INSERT INTO app (name, database_name, collection_name, created_at) VALUES (?, ?, ?, ?)",
    ).use { insert ->
        insert.setString(1, name)
        insert.setString(2, "db")
        insert.setString(3, "octometer_events")
        insert.setLong(4, 1_700_000_000_000L)
        insert.executeUpdate()
    }
    return writer.createStatement().use { statement ->
        statement.executeQuery("SELECT last_insert_rowid()").use { result ->
            result.next()
            result.getLong(1)
        }
    }
}

private fun countApps(connection: Connection): Int =
    connection.createStatement().use { statement ->
        statement.executeQuery("SELECT COUNT(*) FROM app").use { result ->
            result.next()
            result.getInt(1)
        }
    }
