package octometer.monitor.config

/** The origin of one resolved config value, for the start log of D2. */
enum class ConfigSource(val label: String) {
    ARGUMENT("the argument"),
    ENVIRONMENT_VARIABLE("the environment variable"),
    USER_FILE("the user file"),
    BUNDLED_DEFAULT("the bundled default"),
}
