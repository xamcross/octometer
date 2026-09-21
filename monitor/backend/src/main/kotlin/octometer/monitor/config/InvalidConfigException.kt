package octometer.monitor.config

/** An invalid config value. The caller of loadConfig exits with code 2. */
class InvalidConfigException(message: String) : Exception(message)
