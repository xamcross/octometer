package octometer.monitor.store

import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlinx.coroutines.runBlocking
import octometer.monitor.registerTempRoot
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    // SQLite MAJOR 1 of correction round 1: dataDir sits under root, so
    // the sibling backups folder of issue #55 stays inside root.
    private val root =
        Files.createTempDirectory("octometer-read-transaction-test-").toFile().also { registerTempRoot(it) }
    private val dataDir = File(root, "data")
    private lateinit var database: SqliteDatabase

    @BeforeTest
    fun setUp() = runBlocking {
        database = SqliteDatabase.open(dataDir.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        database.close()
        root.deleteRecursively()
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

// Decision 1 of issue #58, the correction of 2026-09-27: random bytes
// give SQLITE_NOTADB while SqlitePragmas.applyTo reads the file header,
// before PRAGMA quick_check ever runs. The exception text is thus the
// driver text, never a quick_check row. The file itself stays as it is:
// SqliteDatabase.open writes nothing before this check runs.
class SqliteDatabaseCorruptTest {

    private val root = Files.createTempDirectory("octometer-corrupt-test-").toFile().also { registerTempRoot(it) }
    private val dataDir = File(root, "data")

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `random bytes in octometer db give CorruptDatabaseException with the driver text, and the file stays as it is`() {
        dataDir.mkdirs()
        val dbFile = File(dataDir, "octometer.db")
        val randomBytes = ByteArray(4096)
        SecureRandom().nextBytes(randomBytes)
        dbFile.writeBytes(randomBytes)
        val bytesBeforeOpen = dbFile.readBytes()

        val failure = assertFailsWith<CorruptDatabaseException> {
            SqliteDatabase.open(dataDir.absolutePath)
        }

        assertTrue(
            failure.message!!.contains("SQLITE_NOTADB", ignoreCase = true) ||
                failure.message!!.contains("not a database", ignoreCase = true),
            "the message names the driver text: ${failure.message}",
        )
        assertFalse(
            failure.message!!.contains("quick_check"),
            "the random-bytes path never gives the quick_check text",
        )
        assertTrue(bytesBeforeOpen.contentEquals(dbFile.readBytes()), "the file stays as it is")
    }

    // A file that quick_check itself catches: a good header (the file
    // opens, and journal_mode=WAL applies), with its last page cut off.
    // The header's own page count then no longer matches the file size,
    // and quick_check gives "database disk image is malformed". This
    // fails before MigrationRunner.run ever writes anything (decision
    // 1's correction of 2026-09-27).
    @Test
    fun `a file cut off after its header fails PRAGMA quick_check`() {
        dataDir.mkdirs()
        val dbFile = File(dataDir, "octometer.db")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE probe (id INTEGER, filler TEXT)") }
            connection.prepareStatement("INSERT INTO probe (id, filler) VALUES (?, ?)").use { insert ->
                val filler = "x".repeat(500)
                for (i in 1..200) {
                    insert.setInt(1, i)
                    insert.setString(2, filler)
                    insert.executeUpdate()
                }
            }
        }
        val fullLength = dbFile.length()
        // Keep the first page (the header) whole, and cut the file well
        // before its recorded end, so the last pages are simply gone.
        val truncatedLength = minOf(4096L * 2, fullLength - 4096L)
        java.io.RandomAccessFile(dbFile, "rw").use { it.setLength(truncatedLength) }

        val failure = assertFailsWith<CorruptDatabaseException> {
            SqliteDatabase.open(dataDir.absolutePath)
        }

        assertTrue(
            failure.message!!.contains("quick_check") || failure.message!!.contains("malformed", ignoreCase = true),
            "the message names the quick_check row: ${failure.message}",
        )
    }

    // A direct test of the shared helper itself (decision 1: SqliteDatabase.open
    // and DatabaseBackup.checkIntegrity both call runQuickCheck). A
    // connection with no journal_mode=WAL pragma applied reaches this
    // corrupt page with no earlier driver failure, so this proves the
    // "answer != ok" branch of refuseIfCorrupt on its own, distinct from
    // a driver failure at connection open.
    @Test
    fun `runQuickCheck gives a row other than ok for a page corrupted after the header`() {
        dataDir.mkdirs()
        val dbFile = File(dataDir, "octometer.db")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE probe (id INTEGER, filler TEXT)") }
            connection.prepareStatement("INSERT INTO probe (id, filler) VALUES (?, ?)").use { insert ->
                val filler = "y".repeat(500)
                for (i in 1..200) {
                    insert.setInt(1, i)
                    insert.setString(2, filler)
                    insert.executeUpdate()
                }
            }
        }
        val corrupted = dbFile.readBytes().copyOf()
        // Page 3 (offset 8192, the default page size 4096) holds table
        // rows, well past the 100-byte file header of page 1. A byte
        // flip well inside it corrupts a cell, not a page boundary.
        val corruptFrom = 8192 + 500
        for (i in corruptFrom until corruptFrom + 64) {
            corrupted[i] = (corrupted[i] + 1).toByte()
        }
        dbFile.writeBytes(corrupted)

        val answer = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            runQuickCheck(connection)
        }

        assertTrue(answer != "ok", "a corrupted page gives a row other than ok: $answer")
    }

    // Correction round 1, MAJOR B (Kotlin and SQLite review): the review
    // found that no test called SqliteDatabase.open itself on a file
    // where PRAGMA journal_mode=WAL succeeds and PRAGMA quick_check then
    // reports a bad page (the "answer != ok" throw of refuseIfCorrupt).
    // This test uses the same page-3 fixture as the direct runQuickCheck
    // test above, through the full open() call.
    @Test
    fun `SqliteDatabase open throws with the quick_check text for a page corrupted after the header`() {
        dataDir.mkdirs()
        val dbFile = File(dataDir, "octometer.db")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE probe (id INTEGER, filler TEXT)") }
            connection.prepareStatement("INSERT INTO probe (id, filler) VALUES (?, ?)").use { insert ->
                val filler = "z".repeat(500)
                for (i in 1..200) {
                    insert.setInt(1, i)
                    insert.setString(2, filler)
                    insert.executeUpdate()
                }
            }
        }
        val corrupted = dbFile.readBytes().copyOf()
        val corruptFrom = 8192 + 500
        for (i in corruptFrom until corruptFrom + 64) {
            corrupted[i] = (corrupted[i] + 1).toByte()
        }
        dbFile.writeBytes(corrupted)

        val failure = assertFailsWith<CorruptDatabaseException> {
            SqliteDatabase.open(dataDir.absolutePath)
        }

        assertTrue(
            failure.message!!.contains("quick_check"),
            "the answer != ok throw of refuseIfCorrupt names the quick_check row: ${failure.message}",
        )
    }
}

// Decision 6 of issue #58: no ERROR line of the corrupt-database path or
// the lock-failure path holds the data folder path, only a file name.
class SqliteDatabaseCorruptLogMarkerTest {

    private val root = Files.createTempDirectory("octometer-corrupt-marker-test-").toFile().also { registerTempRoot(it) }
    private val dataDir = File(root, "data")

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `the CorruptDatabaseException message never holds the data folder path`() {
        dataDir.mkdirs()
        val dbFile = File(dataDir, "octometer.db")
        val randomBytes = ByteArray(4096)
        SecureRandom().nextBytes(randomBytes)
        dbFile.writeBytes(randomBytes)

        val failure = assertFailsWith<CorruptDatabaseException> {
            SqliteDatabase.open(dataDir.absolutePath)
        }

        assertFalse(
            failure.message!!.contains(dataDir.absolutePath),
            "the message names a file name only, never the data folder path",
        )
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
