package octometer.monitor.backup

import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Step 1 of issue #55: the backup file names and folder of D3 and D34.
// Each test here uses a fixed Clock, never a real clock and never a sleep.
class BackupPathsTest {

    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-01-05T10:15:30Z"), ZoneOffset.UTC)

    @Test
    fun `backupsDir names the sibling folder of the data folder`() {
        val dataDir = File("C:/example/data").absolutePath
        val result = backupsDir(dataDir)
        assertEquals(File("C:/example/backups").absolutePath, result.absolutePath)
    }

    @Test
    fun `dailyBackupFileName holds the date and no other text`() {
        assertEquals("octometer-20260105.db", dailyBackupFileName(fixedClock))
    }

    @Test
    fun `preMigrateBackupFileName holds the schema version and a timestamp`() {
        val name = preMigrateBackupFileName(fromVersion = 2, clock = fixedClock)
        assertTrue(name.startsWith("pre-migrate-v2-"), "the name starts with the schema version")
        assertTrue(name.endsWith(".db"), "the name ends with .db")
        assertFalse(name.contains(":"), "a colon is not a valid Windows file name character")
    }

    @Test
    fun `isDailyBackupDue is true the first time, and true only after the date changes`() {
        val sameDayLater = Clock.fixed(Instant.parse("2026-01-05T23:59:00Z"), ZoneOffset.UTC)
        val nextDay = Clock.fixed(Instant.parse("2026-01-06T00:00:01Z"), ZoneOffset.UTC)

        assertTrue(isDailyBackupDue(fixedClock, lastBackupDate = null), "no backup ran yet")
        assertFalse(
            isDailyBackupDue(sameDayLater, lastBackupDate = LocalDate.of(2026, 1, 5)),
            "the calendar day did not change yet",
        )
        assertTrue(
            isDailyBackupDue(nextDay, lastBackupDate = LocalDate.of(2026, 1, 5)),
            "the calendar day changed",
        )
    }

    private val tempDir = Files.createTempDirectory("octometer-backup-paths-test-").toFile()

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `pruneDailyBackups keeps only the newest count of daily files`() {
        val names = listOf(
            "octometer-20260101.db",
            "octometer-20260102.db",
            "octometer-20260103.db",
            "octometer-20260104.db",
            "octometer-20260105.db",
        )
        for (name in names) File(tempDir, name).writeText("db")

        val deleted = pruneDailyBackups(tempDir, retainedCount = 3)

        assertEquals(setOf("octometer-20260101.db", "octometer-20260102.db"), deleted.map { it.name }.toSet())
        val remaining = tempDir.listFiles()!!.map { it.name }.toSet()
        assertEquals(setOf("octometer-20260103.db", "octometer-20260104.db", "octometer-20260105.db"), remaining)
    }

    @Test
    fun `pruneDailyBackups never deletes a file the monitor did not make`() {
        File(tempDir, "octometer-20260101.db").writeText("db")
        File(tempDir, "octometer-20260102.db").writeText("db")
        val foreignFile = File(tempDir, "notes.txt").apply { writeText("keep me") }
        val migrationFile = File(tempDir, "pre-migrate-v1-20260101010101.db").apply { writeText("keep me too") }

        pruneDailyBackups(tempDir, retainedCount = 1)

        assertTrue(foreignFile.isFile, "a file with a different name stays")
        assertTrue(migrationFile.isFile, "a pre-migration backup stays")
    }
}
