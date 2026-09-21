package octometer.monitor.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import java.io.File

// The argument form of D2: -P:octometer.<key>=<value>.
private val ARGUMENT_PATTERN = Regex("""^-P:octometer\.([a-zA-Z]+)=(.*)$""")

// The environment variable name of each key, from D2.
private val ENVIRONMENT_VARIABLE_NAMES = mapOf(
    "mode" to "OCTOMETER_MODE",
    "port" to "OCTOMETER_PORT",
    "dataDir" to "OCTOMETER_DATA_DIR",
    "settleLagSeconds" to "OCTOMETER_SETTLE_LAG_SECONDS",
    "retentionDays" to "OCTOMETER_STORE_RETENTION_DAYS",
)

/**
 * Load the monitor config one time, at the start, with the fixed precedence
 * of D2. The order, high to low: the argument, the environment variable, the
 * user file, the bundled default.
 *
 * @param args the program arguments. One argument has the form
 *   `-P:octometer.<key>=<value>`.
 * @param env the environment variables. The default is the real environment.
 * @param userConfigFile the user config file. A test gives its own file, and
 *   never the real file at `%LOCALAPPDATA%\Octometer\octometer.conf`.
 * @throws InvalidConfigException when one value fails validation.
 */
fun loadConfig(
    args: Array<String> = emptyArray(),
    env: Map<String, String> = System.getenv(),
    userConfigFile: File = defaultUserConfigFile(),
): ResolvedConfig {
    val arguments = parseArguments(args)
    val bundled = ConfigFactory.parseResources("application.conf").resolve()
    val userConfig = if (userConfigFile.isFile) {
        ConfigFactory.parseFile(userConfigFile).resolve()
    } else {
        ConfigFactory.empty()
    }

    val mode = resolveValue("mode", arguments, env, userConfig, bundled.getString("octometer.mode"))
    validateMode(mode.value)
    val modeDefaults = bundled.getConfig("octometer.${mode.value}")

    val port = resolveValue("port", arguments, env, userConfig, bundled.getString("octometer.port"))
    val dataDir = resolveValue("dataDir", arguments, env, userConfig, modeDefaults.getString("dataDir"))
    val settleLagSeconds = resolveValue(
        "settleLagSeconds",
        arguments,
        env,
        userConfig,
        modeDefaults.getString("settleLagSeconds"),
    )
    val retentionDays = resolveValue(
        "retentionDays",
        arguments,
        env,
        userConfig,
        bundled.getString("octometer.retentionDays"),
    )

    val config = MonitorConfig(
        mode = mode.value,
        port = toValidInt("port", port.value, 1..65535),
        dataDir = validateDataDir(dataDir.value),
        settleLagSeconds = toValidInt("settleLagSeconds", settleLagSeconds.value, 1..Int.MAX_VALUE),
        retentionDays = toValidInt("retentionDays", retentionDays.value, 1..Int.MAX_VALUE),
    )

    return ResolvedConfig(
        config = config,
        values = listOf(
            ResolvedValue("mode", mode.value, mode.source),
            ResolvedValue("port", port.value, port.source),
            ResolvedValue("dataDir", dataDir.value, dataDir.source),
            ResolvedValue("settleLagSeconds", settleLagSeconds.value, settleLagSeconds.source),
            ResolvedValue("retentionDays", retentionDays.value, retentionDays.source),
        ),
    )
}

/** The real user config file, at `%LOCALAPPDATA%\Octometer\octometer.conf`. */
fun defaultUserConfigFile(): File {
    val localAppData = System.getenv("LOCALAPPDATA")
        ?: throw InvalidConfigException("The environment has no LOCALAPPDATA variable.")
    return File(localAppData, "Octometer${File.separator}octometer.conf")
}

private fun parseArguments(args: Array<String>): Map<String, String> {
    val result = mutableMapOf<String, String>()
    for (argument in args) {
        val match = ARGUMENT_PATTERN.matchEntire(argument) ?: continue
        result[match.groupValues[1]] = match.groupValues[2]
    }
    return result
}

private data class RawValue(val value: String, val source: ConfigSource)

private fun resolveValue(
    key: String,
    arguments: Map<String, String>,
    env: Map<String, String>,
    userConfig: Config,
    bundledValue: String,
): RawValue {
    arguments[key]?.let { return RawValue(it, ConfigSource.ARGUMENT) }
    val environmentVariableName = ENVIRONMENT_VARIABLE_NAMES.getValue(key)
    env[environmentVariableName]?.let { return RawValue(it, ConfigSource.ENVIRONMENT_VARIABLE) }
    // The user file octometer.conf holds each key at its top level, with no
    // wrapping object, because the file name already names the app.
    if (userConfig.hasPath(key)) {
        return RawValue(userConfig.getString(key), ConfigSource.USER_FILE)
    }
    return RawValue(bundledValue, ConfigSource.BUNDLED_DEFAULT)
}

private fun validateMode(value: String) {
    if (value != "dev" && value != "prod") {
        throw InvalidConfigException("The value of mode is '$value'. Set it to 'dev' or 'prod'.")
    }
}

private fun validateDataDir(value: String): String {
    if (value.isBlank()) {
        throw InvalidConfigException("The value of dataDir is blank. Give a folder path.")
    }
    return value
}

private fun toValidInt(key: String, value: String, range: IntRange): Int {
    val parsed = value.toIntOrNull()
    if (parsed == null || parsed !in range) {
        throw InvalidConfigException("The value of $key is '$value'. Give a whole number in $range.")
    }
    return parsed
}
