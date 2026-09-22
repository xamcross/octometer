package octometer.monitor.backup

import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val BACKUPS_FOLDER_NAME = "backups"
private val DAILY_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
private val PRE_MIGRATE_TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
private val DAILY_BACKUP_PATTERN = Regex("^octometer-(\\d{8})\\.db$")

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
 */
fun isDailyBackupDue(clock: Clock, lastBackupDate: LocalDate?): Boolean =
    lastBackupDate == null || LocalDate.now(clock) != lastBackupDate

/**
 * Deletes each daily backup file beyond the newest [retainedCount] (step
 * 3). It reads only file names of the exact form
 * `octometer-<yyyyMMdd>.db`, so a different file in the folder, for
 * example a pre-migration backup, never gets deleted. Returns the deleted
 * files.
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
    return toDelete
}
