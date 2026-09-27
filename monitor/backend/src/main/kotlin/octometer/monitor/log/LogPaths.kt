package octometer.monitor.log

import java.io.File

private const val LOGS_FOLDER_NAME = "logs"

/**
 * The logs folder of D34 and issue #57: a sibling of the data folder,
 * next to `secrets` and `backups`. The form is the same as
 * [octometer.monitor.backup.backupsDir].
 */
fun logsDir(dataDir: String): File =
    File(File(dataDir).absoluteFile.parentFile ?: File("."), LOGS_FOLDER_NAME)
