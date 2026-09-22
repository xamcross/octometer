package octometer.monitor.backup

import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import octometer.monitor.registerTempRoot
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

    // SQLite MINOR 8 of correction round 1: a resolution of one second let
    // two migrations inside that second overwrite each other. The
    // milliseconds give each call inside the fixed instant its own name.
    @Test
    fun `preMigrateBackupFileName gives a different name for two calls at the same second`() {
        val firstCallClock = Clock.fixed(Instant.parse("2026-01-05T10:15:30.001Z"), ZoneOffset.UTC)
        val secondCallClock = Clock.fixed(Instant.parse("2026-01-05T10:15:30.002Z"), ZoneOffset.UTC)

        val firstName = preMigrateBackupFileName(fromVersion = 1, clock = firstCallClock)
        val secondName = preMigrateBackupFileName(fromVersion = 1, clock = secondCallClock)

        assertFalse(firstName == secondName, "two calls inside one second give two names")
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

    // Reliability MINOR 1 of correction round 1: a backward clock change
    // over midnight must never make the job due again for an earlier day.
    // The old `!=` comparison gave true here; `isAfter` gives false.
    @Test
    fun `isDailyBackupDue is false when the clock moves backward across midnight`() {
        val backwardClock = Clock.fixed(Instant.parse("2026-01-08T23:55:00Z"), ZoneOffset.UTC)

        assertFalse(
            isDailyBackupDue(backwardClock, lastBackupDate = LocalDate.of(2026, 1, 9)),
            "a clock behind lastBackupDate must never be due",
        )
    }

    private val tempDir = Files.createTempDirectory("octometer-backup-paths-test-").toFile().also { registerTempRoot(it) }

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
        val migrationFile = File(tempDir, "pre-migrate-v1-20260101010101000.db").apply { writeText("keep me too") }

        // SQLite MINOR 3 of correction round 1: retainedCount = 1 let both
        // daily files stay, thus a widened pattern that also matched the
        // two foreign names could not fail this test. retainedCount = 0
        // deletes every daily file, so a wrong pattern match shows at once.
        pruneDailyBackups(tempDir, retainedCount = 0)

        assertTrue(foreignFile.isFile, "a file with a different name stays")
        assertTrue(migrationFile.isFile, "a pre-migration backup stays")
    }

    // SQLite MAJOR 2 of correction round 1: the evidence test of the pull
    // request called pruneDailyBackups with retainedCount = 3, its own
    // value, thus it never read RETAINED_DAILY_BACKUPS. This test uses
    // the named constant, so a change of its value shows here at once.
    @Test
    fun `pruneDailyBackups with RETAINED_DAILY_BACKUPS keeps the newest 7 files, and a foreign file stays`() {
        val names = (1..9).map { day -> "octometer-202601%02d.db".format(day) }
        for (name in names) File(tempDir, name).writeText("db")
        val foreignFile = File(tempDir, "owner-notes.txt").apply { writeText("keep me") }

        val deleted = pruneDailyBackups(tempDir, retainedCount = RETAINED_DAILY_BACKUPS)

        assertEquals(setOf("octometer-20260101.db", "octometer-20260102.db"), deleted.map { it.name }.toSet())
        val remainingDaily = tempDir.listFiles()!!.map { it.name }.filter { it.startsWith("octometer-") }
        assertEquals(RETAINED_DAILY_BACKUPS, remainingDaily.size, "the job keeps 7 files")
        assertTrue(foreignFile.isFile, "a foreign file in the folder stays")
    }

    @Test
    fun `sweepStaleTempFiles removes each stale temporary file, and keeps a real backup`() {
        val staleDaily = File(tempDir, "octometer-20260101.db.tmp").apply { writeText("stale") }
        val staleMigration = File(tempDir, "pre-migrate-v1-20260101010101000.db.tmp").apply { writeText("stale") }
        val realBackup = File(tempDir, "octometer-20260105.db").apply { writeText("db") }

        val deleted = sweepStaleTempFiles(tempDir)

        assertEquals(setOf("octometer-20260101.db.tmp", "pre-migrate-v1-20260101010101000.db.tmp"), deleted.map { it.name }.toSet())
        assertFalse(staleDaily.isFile, "the stale daily temp file is gone")
        assertFalse(staleMigration.isFile, "the stale pre-migration temp file is gone")
        assertTrue(realBackup.isFile, "a real backup file stays")
    }
}
