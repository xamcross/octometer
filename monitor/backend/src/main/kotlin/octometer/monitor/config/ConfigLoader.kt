package octometer.monitor.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import java.io.File
import octometer.monitor.backup.backupsDir

// The argument form of D2: -P:octometer.<key>=<value>.
private val ARGUMENT_PATTERN = Regex("""^-P:octometer\.([a-zA-Z]+)=(.*)$""")

// The environment variable name of each key, from D2.
private val ENVIRONMENT_VARIABLE_NAMES = mapOf(
    "mode" to "OCTOMETER_MODE",
    "port" to "OCTOMETER_PORT",
    "dataDir" to "OCTOMETER_DATA_DIR",
    "settleLagSeconds" to "OCTOMETER_SETTLE_LAG_SECONDS",
    "retentionDays" to "OCTOMETER_STORE_RETENTION_DAYS",
    "backupDir" to "OCTOMETER_BACKUP_DIR",
    "pollIntervalSeconds" to "OCTOMETER_POLL_INTERVAL_SECONDS",
)

private val KNOWN_KEYS = ENVIRONMENT_VARIABLE_NAMES.keys

// The fallback folder of D2 for an environment with no LOCALAPPDATA
// variable, for example a Linux or a macOS test runner.
private const val FALLBACK_DATA_DIR_SUFFIX = ".octometer/data"
private const val FALLBACK_USER_CONFIG_SUFFIX = ".octometer/octometer.conf"

/**
 * Load the monitor config one time, at the start, with the fixed precedence
 * of D2. The order, high to low: the argument, the environment variable, the
 * user file, the bundled default.
 *
 * The user file and the bundled file use the same shape, one `octometer { }`
 * block, the same prefix as the argument `-P:octometer.<key>=`. Example of
 * `octometer.conf`:
 * ```
 * octometer {
 *   mode = "dev"
 *   port = 7999
 *   dataDir = "D:/octometer-data"
 * }
 * ```
 * A Windows path inside the file needs a forward slash or two backslashes,
 * because a quoted HOCON string reads one backslash as an escape.
 *
 * A value may be a quoted string, a plain number, or a boolean; each becomes
 * text. A list or an object is not a value, and it gives an invalid-config
 * error.
 *
 * @param args the program arguments. One argument has the form
 *   `-P:octometer.<key>=<value>`.
 * @param env the environment variables. The default is the real environment.
 * @param userConfigFile the user config file. A test gives its own file, and
 *   never the real file at `%LOCALAPPDATA%\Octometer\octometer.conf`.
 * @throws InvalidConfigException when one value fails validation, or when
 *   the user file has a syntax error, or the process cannot read it.
 */
fun loadConfig(
    args: Array<String> = emptyArray(),
    env: Map<String, String> = System.getenv(),
    userConfigFile: File = defaultUserConfigFile(env),
): ResolvedConfig {
    try {
        val arguments = parseArguments(args)
        val bundled = ConfigFactory.parseResources("octometer-defaults.conf")
        val userConfig = readUserConfig(userConfigFile)

        val warnings = mutableListOf<String>()
        warnStrayTopLevelKeys(userConfig, warnings)
        warnUnknownKeys(arguments.keys, userConfig, warnings)

        val mode = resolveValue("mode", arguments, env, userConfig, bundled.getString("octometer.mode"))
        validateMode(mode.value)
        val modeDefaults = bundled.getConfig("octometer.${mode.value}")

        val port = resolveValue("port", arguments, env, userConfig, bundled.getString("octometer.port"))
        val dataDir = resolveValue(
            "dataDir",
            arguments,
            env,
            userConfig,
            bundledDataDirDefault(mode.value, env, modeDefaults),
        )
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
        // Issue #17: pollIntervalSeconds follows the mode, the same rule
        // as settleLagSeconds (5 s dev, 60 s prod, design decision D6).
        val pollIntervalSeconds = resolveValue(
            "pollIntervalSeconds",
            arguments,
            env,
            userConfig,
            modeDefaults.getString("pollIntervalSeconds"),
        )
        val validatedDataDir = validateDataDir(dataDir.value)
        // Issue #55: the bundled default is the folder "backups" beside
        // dataDir. The value depends on the resolved dataDir, so this
        // computes it in code, the same pattern as the prod dataDir default.
        val backupDir = resolveValue(
            "backupDir",
            arguments,
            env,
            userConfig,
            backupsDir(validatedDataDir).absolutePath,
        )

        val config = MonitorConfig(
            mode = mode.value,
            port = toValidInt("port", port.value, 1..65535, "1 to 65535"),
            dataDir = validatedDataDir,
            settleLagSeconds = toValidInt("settleLagSeconds", settleLagSeconds.value, 0..Int.MAX_VALUE, "0 or more"),
            retentionDays = toValidInt("retentionDays", retentionDays.value, 1..Int.MAX_VALUE, "1 or more"),
            backupDir = validateBackupDir(backupDir.value),
            pollIntervalSeconds = toValidInt(
                "pollIntervalSeconds",
                pollIntervalSeconds.value,
                1..Int.MAX_VALUE,
                "1 or more",
            ),
        )

        return ResolvedConfig(
            config = config,
            values = listOf(
                ResolvedValue("mode", config.mode, mode.source),
                ResolvedValue("port", config.port.toString(), port.source),
                ResolvedValue("dataDir", File(config.dataDir).absolutePath, dataDir.source),
                ResolvedValue("settleLagSeconds", config.settleLagSeconds.toString(), settleLagSeconds.source),
                ResolvedValue("retentionDays", config.retentionDays.toString(), retentionDays.source),
                ResolvedValue("backupDir", File(config.backupDir).absolutePath, backupDir.source),
                ResolvedValue("pollIntervalSeconds", config.pollIntervalSeconds.toString(), pollIntervalSeconds.source),
            ),
            warnings = warnings,
        )
    } catch (configException: ConfigException) {
        throw InvalidConfigException(describeConfigException(userConfigFile, configException))
    }
}

/**
 * The real user config file: `%LOCALAPPDATA%\Octometer\octometer.conf`, or
 * the fallback path of D2 in the user home folder when the environment has
 * no `LOCALAPPDATA` variable.
 */
fun defaultUserConfigFile(env: Map<String, String> = System.getenv()): File {
    val localAppData = env["LOCALAPPDATA"]
    return if (localAppData != null) {
        File(localAppData, "Octometer${File.separator}octometer.conf")
    } else {
        File(System.getProperty("user.home"), FALLBACK_USER_CONFIG_SUFFIX.replace('/', File.separatorChar))
    }
}

// D2: dataDir defaults to %LOCALAPPDATA%\Octometer\data in prod mode, and to
// build/dev-data in dev mode. The prod value comes from the injected env
// map, never from System.getenv, so a test controls it. With no
// LOCALAPPDATA variable, prod mode falls back to <user home>/.octometer/data.
private fun bundledDataDirDefault(mode: String, env: Map<String, String>, modeDefaults: Config): String {
    if (mode != "prod") {
        return modeDefaults.getString("dataDir")
    }
    val localAppData = env["LOCALAPPDATA"]
    return if (localAppData != null) {
        "$localAppData\\Octometer\\data"
    } else {
        "${System.getProperty("user.home")}/$FALLBACK_DATA_DIR_SUFFIX"
    }
}

private fun readUserConfig(userConfigFile: File): Config {
    if (!userConfigFile.isFile) {
        return ConfigFactory.empty()
    }
    // allowMissing = false: the file exists, thus a read error or a syntax
    // error must not fall back to the bundled default in silence.
    val options = ConfigParseOptions.defaults().setAllowMissing(false)
    return ConfigFactory.parseFile(userConfigFile, options)
}

private fun describeConfigException(userConfigFile: File, error: ConfigException): String {
    val lineNumber = error.origin()?.lineNumber()
    val location = if (lineNumber != null && lineNumber > 0) " at line $lineNumber" else ""
    val hint = if (error is ConfigException.Parse) {
        " Write a Windows path with a forward slash, or with two backslashes."
    } else {
        ""
    }
    return "The file '${userConfigFile.path}' has an error$location. ${error.message}$hint"
}

private fun parseArguments(args: Array<String>): Map<String, String> {
    val result = mutableMapOf<String, String>()
    for (argument in args) {
        val match = ARGUMENT_PATTERN.matchEntire(argument) ?: continue
        result[match.groupValues[1]] = match.groupValues[2]
    }
    return result
}

private fun warnStrayTopLevelKeys(userConfig: Config, warnings: MutableList<String>) {
    for (key in userConfig.root().keys) {
        if (key != "octometer") {
            warnings += "The user file has the key '${escapeForLog(key)}' outside the octometer " +
                "block. Wrap each key in octometer { }."
        }
    }
}

private fun warnUnknownKeys(argumentKeys: Set<String>, userConfig: Config, warnings: MutableList<String>) {
    for (key in argumentKeys) {
        if (key !in KNOWN_KEYS) {
            warnings += "The argument -P:octometer.${escapeForLog(key)} names an unknown key. " +
                "Octometer ignores it."
        }
    }
    if (userConfig.hasPath("octometer")) {
        for (key in userConfig.getConfig("octometer").root().keys) {
            if (key !in KNOWN_KEYS) {
                warnings += "The user file has the unknown key 'octometer.${escapeForLog(key)}'. " +
                    "Octometer ignores it."
            }
        }
    }
}

private data class RawValue(val value: String, val source: ConfigSource)

private fun resolveValue(
    key: String,
    arguments: Map<String, String>,
    env: Map<String, String>,
    userConfig: Config,
    bundledValue: String,
): RawValue {
    arguments[key]?.let { return RawValue(it.trim(), ConfigSource.ARGUMENT) }
    val environmentVariableName = ENVIRONMENT_VARIABLE_NAMES.getValue(key)
    env[environmentVariableName]?.let { return RawValue(it.trim(), ConfigSource.ENVIRONMENT_VARIABLE) }
    // The user file holds each key inside the octometer block, the same
    // shape as the bundled file.
    val path = "octometer.$key"
    if (userConfig.hasPath(path)) {
        return RawValue(userConfig.getString(path).trim(), ConfigSource.USER_FILE)
    }
    return RawValue(bundledValue.trim(), ConfigSource.BUNDLED_DEFAULT)
}

private fun validateMode(value: String) {
    if (Mode.fromValue(value) == null) {
        throw InvalidConfigException("The value of mode is '$value'. Set it to 'dev' or 'prod'.")
    }
}

private fun validateDataDir(value: String): String {
    if (value.isBlank()) {
        throw InvalidConfigException("The value of dataDir is blank. Give a folder path.")
    }
    return value
}

private fun validateBackupDir(value: String): String {
    if (value.isBlank()) {
        throw InvalidConfigException("The value of backupDir is blank. Give a folder path.")
    }
    return value
}

private fun toValidInt(key: String, value: String, range: IntRange, rangeText: String): Int {
    val parsed = value.toIntOrNull()
    if (parsed == null || parsed !in range) {
        throw InvalidConfigException("The value of $key is '$value'. Give a whole number of $rangeText.")
    }
    return parsed
}
