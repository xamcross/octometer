package octometer.monitor.config

/**
 * Turn each control character of a value into a visible escape. A crafted
 * config value must not forge an extra line of the start log.
 */
fun escapeForLog(value: String): String = buildString {
    for (char in value) {
        when {
            char == '\n' -> append("\\n")
            char == '\r' -> append("\\r")
            char == '\t' -> append("\\t")
            char.isISOControl() -> append("\\u%04x".format(char.code))
            else -> append(char)
        }
    }
}
