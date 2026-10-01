package octometer.monitor.config

import octometer.monitor.backup.backupsDir

/**
 * The seven config values of D2, issue #17, and issue #55, after
 * validation.
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
    // Issue #205, the D5 rule of 2026-09-27: the per-app cap on the
    // count of distinct path values of app_path.
    val pathValueCap: Int = 200,
    // Issue #295 (D52, D56): the hosted-service keys. bind is
    // 127.0.0.1 on a desktop run, or 0.0.0.0 behind Fly. clientIpHeader
    // stays unused until #297. clusterUri and mailApiKey are secrets; a
    // log line and an API answer never hold their value. Issue #301
    // removes the temporary admin guard key adminToken (#295, #307):
    // the session rule of D52 replaces it.
    val bind: String = "127.0.0.1",
    val publicUrl: String = "",
    val clientIpHeader: String = "",
    val clusterUri: String = "",
    val mailApiKey: String = "",
    // Issue #301: the mail keys of D52. mailFrom names the sender
    // address. mailSender picks the Resend implementation or the file
    // implementation of the end-to-end suite. mailDir names the folder
    // of the file implementation.
    val mailFrom: String = "Octometer <no-reply@0ct0meter.com>",
    val mailSender: String = "resend",
    val mailDir: String = "",
)
