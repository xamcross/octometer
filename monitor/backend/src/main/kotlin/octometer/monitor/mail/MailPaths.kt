package octometer.monitor.mail

import java.io.File

private const val MAIL_FOLDER_NAME = "mail"

/**
 * The mail folder of the file sender (issue #301, D52): a sibling of
 * the data folder, the same pattern as `backupsDir`.
 */
fun mailDir(dataDir: String): File =
    File(File(dataDir).absoluteFile.parentFile ?: File("."), MAIL_FOLDER_NAME)
