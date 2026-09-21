package octometer.monitor.config

/** The five config values of D2, after validation. */
data class MonitorConfig(
    val mode: String,
    val port: Int,
    val dataDir: String,
    val settleLagSeconds: Int,
    val retentionDays: Int,
)
