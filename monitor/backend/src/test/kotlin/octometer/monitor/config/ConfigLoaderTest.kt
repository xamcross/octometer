package octometer.monitor.config

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// One test for each precedence level of D2, plus a test for each review
// finding of pull request #86. No test writes to the real file
// %LOCALAPPDATA%\Octometer\octometer.conf. Each test gives its own file
// path, and each dataDir test gives its own env map, so LOCALAPPDATA of the
// real process never reaches the assertion.
class ConfigLoaderTest {

    @Test
    fun `the argument overrides the environment variable, the user file, and the bundled default`() {
        val userFile = userConfigFile("octometer { port = 7000 }")

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
        val userFile = userConfigFile("octometer { port = 7000 }")

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
        val userFile = userConfigFile("octometer { port = 7000 }")

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

    // BLOCKER 1 (pull request #86 review): the prod dataDir default comes
    // from the injected env map, and a test controls it without touching
    // the real environment.
    @Test
    fun `the prod dataDir default builds from the injected LOCALAPPDATA variable`() {
        val userFile = missingUserConfigFile()

        val resolved = loadConfig(
            args = emptyArray(),
            env = mapOf("LOCALAPPDATA" to "C:\\Users\\test\\AppData\\Local"),
            userConfigFile = userFile,
        )

        assertEquals("C:\\Users\\test\\AppData\\Local\\Octometer\\data", resolved.config.dataDir)
    }

    // BLOCKER 1: with no LOCALAPPDATA variable in the injected env map (for
    // example a Linux runner), the prod dataDir falls back, and the loader
    // does not throw.
    @Test
    fun `the prod dataDir falls back when the injected env map has no LOCALAPPDATA variable`() {
        val userFile = missingUserConfigFile()

        val resolved = loadConfig(
            args = emptyArray(),
            env = emptyMap(),
            userConfigFile = userFile,
        )

        val expected = "${System.getProperty("user.home")}/.octometer/data"
        assertEquals(expected, resolved.config.dataDir)
    }

    // SQLite MAJOR 1 of correction round 1 for issue #55: backupDir
    // defaults to the sibling folder "backups" of the resolved dataDir,
    // the rule of the issue.
    @Test
    fun `the bundled default of backupDir is the sibling folder backups of dataDir`() {
        val userFile = missingUserConfigFile()

        val resolved = loadConfig(
            args = arrayOf("-P:octometer.dataDir=C:/example/data"),
            env = emptyMap(),
            userConfigFile = userFile,
        )

        assertEquals(File("C:/example/backups").absolutePath, resolved.config.backupDir)
        assertEquals(ConfigSource.BUNDLED_DEFAULT, resolved.values.single { it.key == "backupDir" }.source)
    }

    @Test
    fun `the environment variable OCTOMETER_BACKUP_DIR overrides the bundled default of backupDir`() {
        val userFile = missingUserConfigFile()

        val resolved = loadConfig(
            args = arrayOf("-P:octometer.dataDir=C:/example/data"),
            env = mapOf("OCTOMETER_BACKUP_DIR" to "D:/example/own-backups"),
            userConfigFile = userFile,
        )

        assertEquals("D:/example/own-backups", resolved.config.backupDir)
        assertEquals(ConfigSource.ENVIRONMENT_VARIABLE, resolved.values.single { it.key == "backupDir" }.source)
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

    // Issue #59: a wrong retentionDays value must stop the start with a
    // clear config error, the same way as each other config key.
    @Test
    fun `a retentionDays of 0 gives an InvalidConfigException`() {
        val userFile = missingUserConfigFile()

        assertFailsWith<InvalidConfigException> {
            loadConfig(
                args = arrayOf("-P:octometer.retentionDays=0"),
                env = emptyMap(),
                userConfigFile = userFile,
            )
        }
    }

    @Test
    fun `a negative retentionDays gives an InvalidConfigException`() {
        val userFile = missingUserConfigFile()

        assertFailsWith<InvalidConfigException> {
            loadConfig(
                args = arrayOf("-P:octometer.retentionDays=-1"),
                env = emptyMap(),
                userConfigFile = userFile,
            )
        }
    }

    @Test
    fun `a retentionDays that is not a number gives an InvalidConfigException`() {
        val userFile = missingUserConfigFile()

        assertFailsWith<InvalidConfigException> {
            loadConfig(
                args = arrayOf("-P:octometer.retentionDays=many"),
                env = emptyMap(),
                userConfigFile = userFile,
            )
        }
    }

    @Test
    fun `a retentionDays of 1 is valid`() {
        val userFile = missingUserConfigFile()

        val resolved = loadConfig(
            args = arrayOf("-P:octometer.retentionDays=1"),
            env = emptyMap(),
            userConfigFile = userFile,
        )

        assertEquals(1, resolved.config.retentionDays)
    }

    // BLOCKER 2: a syntax error in the user file, the single-backslash form
    // of a Windows path, gives exit code 2 through InvalidConfigException,
    // and never a raw ConfigException with a stack trace.
    @Test
    fun `a user file with a syntax error gives an InvalidConfigException that names the file`() {
        val userFile = userConfigFile("octometer { dataDir = \"C:\\Users\\x\\data\" }")

        val error = assertFailsWith<InvalidConfigException> {
            loadConfig(args = emptyArray(), env = emptyMap(), userConfigFile = userFile)
        }

        assertTrue(error.message!!.contains(userFile.path), "the message names the file")
    }

    // MAJOR 4: a user file that exists but that the process cannot read
    // gives exit code 2, and it never falls back to the bundled default in
    // silence.
    @Test
    fun `an unreadable user file gives an InvalidConfigException`() {
        val userFile = userConfigFile("octometer { port = 7000 }")
        val couldMakeUnreadable = userFile.setReadable(false, false)
        try {
            if (!couldMakeUnreadable || userFile.canRead()) {
                // The platform (for example a Windows account with full
                // control) did not let this test remove the read right.
                // A manual check with icacls covers this case instead; see
                // the pull request text.
                return
            }
            assertFailsWith<InvalidConfigException> {
                loadConfig(args = emptyArray(), env = emptyMap(), userConfigFile = userFile)
            }
        } finally {
            userFile.setReadable(true, false)
            userFile.delete()
        }
    }

    // MAJOR 3: a key outside the octometer block, and an unknown key
    // inside it, each give one warning that names the key.
    @Test
    fun `an unknown key of the user file gives one warning that names the key`() {
        val userFile = userConfigFile("port = 7000\noctometer { prot = 9000 }")

        val resolved = loadConfig(args = emptyArray(), env = emptyMap(), userConfigFile = userFile)

        assertTrue(resolved.warnings.any { it.contains("'port'") })
        assertTrue(resolved.warnings.any { it.contains("octometer.prot") })
    }

    // MINOR (both reviews): a trailing space around a value, for example
    // from `set OCTOMETER_PORT=9001 ` in cmd.exe, must not fail validation.
    @Test
    fun `a value with surrounding spaces is trimmed before validation`() {
        val userFile = missingUserConfigFile()

        val resolved = loadConfig(
            args = emptyArray(),
            env = mapOf("OCTOMETER_PORT" to " 9001 "),
            userConfigFile = userFile,
        )

        assertEquals(9001, resolved.config.port)
    }

    // MINOR (Ktor engineer): settleLagSeconds of 0 is a valid value; only a
    // negative value is invalid.
    @Test
    fun `a settleLagSeconds of 0 is valid`() {
        val userFile = missingUserConfigFile()

        val resolved = loadConfig(
            args = arrayOf("-P:octometer.settleLagSeconds=0"),
            env = emptyMap(),
            userConfigFile = userFile,
        )

        assertEquals(0, resolved.config.settleLagSeconds)
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
