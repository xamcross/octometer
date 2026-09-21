package octometer.monitor.config

/** One config value with its source. The start log prints it. */
data class ResolvedValue(val key: String, val value: String, val source: ConfigSource)

/** The valid config, plus each resolved value with its source, for the start log. */
data class ResolvedConfig(val config: MonitorConfig, val values: List<ResolvedValue>)
