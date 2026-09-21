package octometer.monitor.config

/** One config value with its source. The start log prints it. */
data class ResolvedValue(val key: String, val value: String, val source: ConfigSource)

/**
 * The valid config, plus each resolved value with its source, plus each
 * warning about a key the loader ignored, for the start log.
 */
data class ResolvedConfig(
    val config: MonitorConfig,
    val values: List<ResolvedValue>,
    val warnings: List<String> = emptyList(),
)
