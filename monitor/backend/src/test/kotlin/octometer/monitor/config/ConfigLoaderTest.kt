package octometer.monitor.config

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// One test for each precedence level of D2, plus the mode-dependent bundled
// default and the invalid-value rule. No test writes to the real file
// %LOCALAPPDATA%\Octometer\octometer.conf. Each test gives its own file path.
class ConfigLoaderTest {

    @Test
    fun `the argument overrides the environment variable, the user file, and the bundled default`() {
        val userFile = userConfigFile("port = 7000")

        val resolved = loadConfig(
            args = arrayOf("-P:octometer.port=9999"),
            env = mapOf("OCTOMETER_PORT" to "8888"),
            userConfigFile = userFile,
        )

        assertEquals(9999, resolved.config.port)
        assertEquals(ConfigSource.ARGUMENT, resolved.values.single { it.key == "port" }.source)
    }

    @Test
    fun `the environment variable overrides the user file and the bundled default`() {
        val userFile = userConfigFile("port = 7000")

        val resolved = loadConfig(
            args = emptyArray(),
            env = mapOf("OCTOMETER_PORT" to "8888"),
            userConfigFile = userFile,
        )

        assertEquals(8888, resolved.config.port)
        assertEquals(ConfigSource.ENVIRONMENT_VARIABLE, resolved.values.single { it.key == "port" }.source)
    }

    @Test
    fun `the user file overrides the bundled default`() {
        val userFile = userConfigFile("port = 7000")

        val resolved = loadConfig(
            args = emptyArray(),
            env = emptyMap(),
            userConfigFile = userFile,
        )

        assertEquals(7000, resolved.config.port)
        assertEquals(ConfigSource.USER_FILE, resolved.values.single { it.key == "port" }.source)
    }

    @Test
    fun `the bundled default applies when no other source sets a value`() {
        val userFile = missingUserConfigFile()

        val resolved = loadConfig(
            args = emptyArray(),
            env = emptyMap(),
            userConfigFile = userFile,
        )

        assertEquals(7431, resolved.config.port)
        assertEquals("prod", resolved.config.mode)
        assertEquals(ConfigSource.BUNDLED_DEFAULT, resolved.values.single { it.key == "port" }.source)
    }

    @Test
    fun `the bundled default of dataDir and settleLagSeconds depends on the resolved mode`() {
        val userFile = missingUserConfigFile()

        val devResolved = loadConfig(
            args = arrayOf("-P:octometer.mode=dev"),
            env = emptyMap(),
            userConfigFile = userFile,
        )
        val prodResolved = loadConfig(
            args = emptyArray(),
            env = emptyMap(),
            userConfigFile = userFile,
        )

        assertEquals(2, devResolved.config.settleLagSeconds)
        assertEquals("build/dev-data", devResolved.config.dataDir)
        assertEquals(60, prodResolved.config.settleLagSeconds)
        assertEquals(395, prodResolved.config.retentionDays)
    }

    @Test
    fun `an invalid value gives an InvalidConfigException`() {
        val userFile = missingUserConfigFile()

        assertFailsWith<InvalidConfigException> {
            loadConfig(
                args = arrayOf("-P:octometer.port=not-a-number"),
                env = emptyMap(),
                userConfigFile = userFile,
            )
        }
    }

    private fun userConfigFile(content: String): File {
        val file = File.createTempFile("octometer-test-", ".conf")
        file.deleteOnExit()
        file.writeText(content)
        return file
    }

    private fun missingUserConfigFile(): File {
        val file = File.createTempFile("octometer-test-missing-", ".conf")
        file.delete()
        return file
    }
}
