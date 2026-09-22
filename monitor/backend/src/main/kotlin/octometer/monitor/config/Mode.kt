package octometer.monitor.config

/** The two modes of D2. */
enum class Mode(val value: String) {
    DEV("dev"),
    PROD("prod"),
    ;

    companion object {
        fun fromValue(value: String): Mode? = entries.firstOrNull { it.value == value }
    }
}
