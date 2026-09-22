package octometer.monitor.backup

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.sql.DriverManager
import org.junit.jupiter.api.Assumptions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Step 1 of issue #55: VACUUM INTO is safe for a database in WAL mode. The
// method never copies octometer.db with a plain file copy.
class DatabaseBackupTest {

    private val tempDir = Files.createTempDirectory("octometer-database-backup-test-").toFile()
    private val backupsDir = File(tempDir, "backups")

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `the backup file opens as a valid database and holds the same row count as the source`() {
        val sourceFile = File(tempDir, "source.db")
        val connection = DriverManager.getConnection("jdbc:sqlite:${sourceFile.absolutePath}")
        connection.createStatement().use { it.execute("CREATE TABLE event (id INTEGER PRIMARY KEY)") }
        connection.createStatement().use { it.execute("INSERT INTO event VALUES (1), (2), (3)") }

        val backupFile = DatabaseBackup.writeTo(connection, backupsDir, "octometer-20260105.db")
        connection.close()

        assertTrue(backupFile.isFile, "the backup file exists")
        val restored = DriverManager.getConnection("jdbc:sqlite:${backupFile.absolutePath}")
        val count = restored.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM event").use { result ->
                result.next()
                result.getInt(1)
            }
        }
        restored.close()
        assertEquals(3, count, "the backup holds every row of the source")
    }

    @Test
    fun `the backup writes no file under the final name when the write to the temporary name fails`() {
        val sourceFile = File(tempDir, "source.db")
        val connection = DriverManager.getConnection("jdbc:sqlite:${sourceFile.absolutePath}")
        connection.createStatement().use { it.execute("CREATE TABLE event (id INTEGER PRIMARY KEY)") }

        // The backups folder is a plain file, so VACUUM INTO cannot create
        // the temporary file inside it. The write must fail before it
        // reaches the final name.
        backupsDir.parentFile.mkdirs()
        backupsDir.writeText("not a folder")

        assertFailsWith<Exception> {
            DatabaseBackup.writeTo(connection, backupsDir, "octometer-20260105.db")
        }
        connection.close()
    }

    @Test
    fun `a second backup replaces a stale final file of an earlier run`() {
        val sourceFile = File(tempDir, "source.db")
        val connection = DriverManager.getConnection("jdbc:sqlite:${sourceFile.absolutePath}")
        connection.createStatement().use { it.execute("CREATE TABLE event (id INTEGER PRIMARY KEY)") }
        connection.createStatement().use { it.execute("INSERT INTO event VALUES (1)") }

        DatabaseBackup.writeTo(connection, backupsDir, "octometer-20260105.db")
        connection.createStatement().use { it.execute("INSERT INTO event VALUES (2)") }
        val secondBackup = DatabaseBackup.writeTo(connection, backupsDir, "octometer-20260105.db")
        connection.close()

        val restored = DriverManager.getConnection("jdbc:sqlite:${secondBackup.absolutePath}")
        val count = restored.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM event").use { result ->
                result.next()
                result.getInt(1)
            }
        }
        restored.close()
        assertEquals(2, count, "the second run replaces the first backup file")
    }

    @Test
    fun `the backup leaves no temporary file behind on Windows, when the final name is locked`() {
        // Windows enforces a byte-range lock against every other handle,
        // this JVM included. A Linux advisory lock does not block a move
        // of a different process, so this test would pass by accident
        // there, and it would prove nothing (rule of SecretStoreTest).
        Assumptions.assumeTrue(
            System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true),
            "the OS must enforce a byte-range lock against a move",
        )
        val sourceFile = File(tempDir, "source.db")
        val connection = DriverManager.getConnection("jdbc:sqlite:${sourceFile.absolutePath}")
        connection.createStatement().use { it.execute("CREATE TABLE event (id INTEGER PRIMARY KEY)") }

        backupsDir.mkdirs()
        val finalFile = File(backupsDir, "octometer-20260105.db")
        finalFile.writeText("old backup")

        // An open file channel with no share-delete option holds a lock
        // that blocks a move over the same path on Windows.
        val channel = FileChannel.open(finalFile.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE)
        val lock = channel.lock()
        try {
            assertFailsWith<Exception> {
                DatabaseBackup.writeTo(connection, backupsDir, "octometer-20260105.db")
            }
        } finally {
            lock.release()
            channel.close()
        }
        connection.close()

        val leftoverTempFiles = backupsDir.listFiles { file -> file.name.endsWith(".tmp") } ?: emptyArray()
        assertTrue(leftoverTempFiles.isEmpty(), "no temporary file stays after a failed move")
    }
}
