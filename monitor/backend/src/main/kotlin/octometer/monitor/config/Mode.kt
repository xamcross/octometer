package octometer.monitor.config

/** The two modes of D2, each with its poll interval of D6 and R2. */
enum class Mode(val value: String, val refreshSeconds: Int) {
    DEV("dev", 5),
    PROD("prod", 60),
    ;

    companion object {
        fun fromValue(value: String): Mode? = entries.firstOrNull { it.value == value }
    }
}
