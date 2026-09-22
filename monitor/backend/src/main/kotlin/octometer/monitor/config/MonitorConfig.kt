package octometer.monitor.config

import octometer.monitor.backup.backupsDir

/**
 * The six config values of D2 and issue #55, after validation.
 *
 * [backupDir] defaults to the folder `backups` beside [dataDir], the rule
 * of issue #55. A test config gives its own value, inside its own root,
 * so a test never writes into a shared folder.
 */
data class MonitorConfig(
    val mode: String,
    val port: Int,
    val dataDir: String,
    val settleLagSeconds: Int,
    val retentionDays: Int,
    val pollIntervalSeconds: Int,
    val backupDir: String = backupsDir(dataDir).absolutePath,
)
