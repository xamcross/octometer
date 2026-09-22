package octometer.monitor.backup

import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("octometer.monitor.backup.BackupPaths")

private const val BACKUPS_FOLDER_NAME = "backups"
private val DAILY_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

// SQLite MINOR 8 of correction round 1: the format now holds the
// milliseconds, so two migrations inside one second give two file names,
// not one file that the second write replaces.
private val PRE_MIGRATE_TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")
private val DAILY_BACKUP_PATTERN = Regex("^octometer-(\\d{8})\\.db$")

// SQLite MAJOR 4 of correction round 1: a temporary file of a killed
// backup, daily or pre-migration, always ends with ".db.tmp".
private val STALE_TEMP_FILE_PATTERN = Regex("^.*\\.db\\.tmp$")

/**
 * The backups folder of D34: a sibling of the data folder, next to
 * `secrets` and `logs`.
 */
fun backupsDir(dataDir: String): File =
    File(File(dataDir).absoluteFile.parentFile ?: File("."), BACKUPS_FOLDER_NAME)

/** The name of the daily backup file of step 1: `octometer-<yyyyMMdd>.db`. */
fun dailyBackupFileName(clock: Clock): String =
    "octometer-${LocalDate.now(clock).format(DAILY_DATE_FORMAT)}.db"

/**
 * The name of the backup file before a migration (D36):
 * `pre-migrate-v<n>-<timestamp>.db`. The timestamp holds no colon, so the
 * name stays valid on Windows.
 */
fun preMigrateBackupFileName(fromVersion: Int, clock: Clock): String {
    val timestamp = LocalDateTime.now(clock).format(PRE_MIGRATE_TIMESTAMP_FORMAT)
    return "pre-migrate-v$fromVersion-$timestamp.db"
}

/**
 * Reports whether the daily backup of step 2 is due. It is due the first
 * time, when [lastBackupDate] is null, and again once the calendar day of
 * [clock] moves past [lastBackupDate].
 *
 * Reliability MINOR 1 of correction round 1: this uses `isAfter`, not
 * `!=`. A backward clock change over midnight then never reports a day
 * before [lastBackupDate] as due, thus the job never writes the file name
 * of an earlier day again.
 */
fun isDailyBackupDue(clock: Clock, lastBackupDate: LocalDate?): Boolean =
    lastBackupDate == null || LocalDate.now(clock).isAfter(lastBackupDate)

/**
 * Deletes each daily backup file beyond the newest [retainedCount] (step
 * 3). It reads only file names of the exact form
 * `octometer-<yyyyMMdd>.db`, so a different file of the folder, for
 * example a pre-migration backup, stays (SQLite MINOR 10 of correction
 * round 1: the active voice, not "never gets deleted"). Returns the
 * deleted files.
 */
fun pruneDailyBackups(backupsDir: File, retainedCount: Int): List<File> {
    val dailyFiles = backupsDir.listFiles()
        ?.filter { file -> file.isFile && DAILY_BACKUP_PATTERN.matches(file.name) }
        ?: emptyList()
    val newestFirst = dailyFiles.sortedByDescending { file -> DAILY_BACKUP_PATTERN.find(file.name)!!.groupValues[1] }
    val toDelete = newestFirst.drop(retainedCount)
    for (file in toDelete) {
        file.delete()
    }
    // Reliability MINOR 3 of correction round 1: a removed backup must
    // leave a record. The line holds a count only, never a file name.
    if (toDelete.isNotEmpty()) {
        log.info("The daily prune removed {} file(s).", toDelete.size)
    }
    return toDelete
}

/**
 * Deletes each stale temporary file of a killed backup (SQLite MAJOR 4 of
 * correction round 1). [DailyBackupJob] calls this at the start, and
 * again at each daily prune, so a crash between the write and the move
 * never leaves a full database copy in the folder for ever. Returns the
 * deleted files.
 */
fun sweepStaleTempFiles(backupsDir: File): List<File> {
    val staleFiles = backupsDir.listFiles()
        ?.filter { file -> file.isFile && STALE_TEMP_FILE_PATTERN.matches(file.name) }
        ?: emptyList()
    for (file in staleFiles) {
        file.delete()
    }
    return staleFiles
}
