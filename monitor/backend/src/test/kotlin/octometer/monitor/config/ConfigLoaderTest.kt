package octometer.monitor.config

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.io.File
import java.sql.DriverManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import octometer.monitor.accounts.insertInvite
import octometer.monitor.allowedHost
import octometer.monitor.captureLogEvents
import octometer.monitor.devConfig
import octometer.monitor.logStart
import octometer.monitor.module
import octometer.monitor.sessionCookieValue
import octometer.monitor.withSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    // Issue #17, decision 4: pollIntervalSeconds follows the mode, the
    // same rule as settleLagSeconds. The test proves the two bundled
    // values of design decision D6: 5 s in dev mode, 60 s in prod mode.
    @Test
    fun `the bundled default of pollIntervalSeconds depends on the resolved mode`() {
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

        assertEquals(5, devResolved.config.pollIntervalSeconds)
        assertEquals(60, prodResolved.config.pollIntervalSeconds)
    }

    @Test
    fun `the OCTOMETER_POLL_INTERVAL_SECONDS variable overrides the bundled default`() {
        val userFile = missingUserConfigFile()

        val resolved = loadConfig(
            args = emptyArray(),
            env = mapOf("OCTOMETER_POLL_INTERVAL_SECONDS" to "15"),
            userConfigFile = userFile,
        )

        assertEquals(15, resolved.config.pollIntervalSeconds)
        assertEquals(
            ConfigSource.ENVIRONMENT_VARIABLE,
            resolved.values.single { it.key == "pollIntervalSeconds" }.source,
        )
    }

    // A tick of 0 s or less gives a poll loop with no gap between two
    // ticks. The loader rejects that value, unlike settleLagSeconds.
    @Test
    fun `a pollIntervalSeconds of 0 is invalid`() {
        val userFile = missingUserConfigFile()

        assertFailsWith<InvalidConfigException> {
            loadConfig(
                args = arrayOf("-P:octometer.pollIntervalSeconds=0"),
                env = emptyMap(),
                userConfigFile = userFile,
            )
        }
    }

    // Issue #17, decision 8: the error names the key and the rule, never
    // the value, unlike the message of each other integer key. A forged
    // value could otherwise reach the start log unescaped.
    @Test
    fun `the pollIntervalSeconds error names the key and the rule, never the value`() {
        val userFile = missingUserConfigFile()

        val error = assertFailsWith<InvalidConfigException> {
            loadConfig(
                args = arrayOf("-P:octometer.pollIntervalSeconds=not-a-number"),
                env = emptyMap(),
                userConfigFile = userFile,
            )
        }

        assertEquals(
            "The value of pollIntervalSeconds is not valid. Give a whole number of 1 or more.",
            error.message,
        )
    }

    // MINOR 1 of the security review of pull request #174: the mode
    // error escapes the value too, the same rule as each integer key.
    // This test gives the value through the environment variable, not
    // the argument. The argument regex has no DOTALL flag. A dot there
    // never matches a newline.
    @Test
    fun `the mode error escapes a newline in the bad value`() {
        val userFile = missingUserConfigFile()

        val error = assertFailsWith<InvalidConfigException> {
            loadConfig(
                args = emptyArray(),
                env = mapOf("OCTOMETER_MODE" to "x\nFORGED LOG LINE"),
                userConfigFile = userFile,
            )
        }

        assertTrue(
            error.message!!.contains("\\n"),
            "the message must escape the newline, not carry it as a raw line break",
        )
    }

    // MINOR 1 of the security review of pull request #174: a newline in
    // a bad value must not forge a line of the start log. The message of
    // each other integer key escapes the value it repeats.
    @Test
    fun `the port error escapes a newline in the bad value, and never forges a log line`() {
        val userFile = missingUserConfigFile()

        val error = assertFailsWith<InvalidConfigException> {
            loadConfig(
                args = emptyArray(),
                env = mapOf("OCTOMETER_PORT" to "3\nFORGED LOG LINE"),
                userConfigFile = userFile,
            )
        }

        assertTrue(
            error.message!!.contains("\\n"),
            "the message must escape the newline, not carry it as a raw line break",
        )
        assertTrue(
            !error.message!!.lines().any { it == "FORGED LOG LINE" },
            "no line of the message may stand alone as a forged log line",
        )
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

    // Issue #205, the D5 rule of 2026-09-27: the bundled default of
    // pathValueCap, the same rule form as retentionDays.
    @Test
    fun `the bundled default of pathValueCap is 200`() {
        val userFile = missingUserConfigFile()

        val resolved = loadConfig(args = emptyArray(), env = emptyMap(), userConfigFile = userFile)

        assertEquals(200, resolved.config.pathValueCap)
        assertEquals(ConfigSource.BUNDLED_DEFAULT, resolved.values.single { it.key == "pathValueCap" }.source)
    }

    @Test
    fun `a pathValueCap of 0 gives an InvalidConfigException`() {
        val userFile = missingUserConfigFile()

        assertFailsWith<InvalidConfigException> {
            loadConfig(
                args = arrayOf("-P:octometer.pathValueCap=0"),
                env = emptyMap(),
                userConfigFile = userFile,
            )
        }
    }

    @Test
    fun `a negative pathValueCap gives an InvalidConfigException`() {
        val userFile = missingUserConfigFile()

        assertFailsWith<InvalidConfigException> {
            loadConfig(
                args = arrayOf("-P:octometer.pathValueCap=-1"),
                env = emptyMap(),
                userConfigFile = userFile,
            )
        }
    }

    @Test
    fun `a pathValueCap that is not a number gives an InvalidConfigException`() {
        val userFile = missingUserConfigFile()

        assertFailsWith<InvalidConfigException> {
            loadConfig(
                args = arrayOf("-P:octometer.pathValueCap=many"),
                env = emptyMap(),
                userConfigFile = userFile,
            )
        }
    }

    @Test
    fun `a pathValueCap of 1 is valid`() {
        val userFile = missingUserConfigFile()

        val resolved = loadConfig(
            args = arrayOf("-P:octometer.pathValueCap=1"),
            env = emptyMap(),
            userConfigFile = userFile,
        )

        assertEquals(1, resolved.config.pathValueCap)
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

    // Issue #295, brief review B1; issue #301 drops adminToken.
    // logStart prints "set" or "not set" for each secret key, never the
    // real value. markerConfigValue, not clusterUri = "...": the
    // implementer rule of the secret scan keeps a secret-shaped literal
    // off a line beside a name that holds the word "key".
    @Test
    fun `logStart prints set or not set for each secret key, never the value`() = runBlocking {
        val userFile = missingUserConfigFile()
        val markerConfigValue = "octoconfigsentinelmarker6a1c"

        val resolved = loadConfig(
            args = emptyArray(),
            env = mapOf(
                "OCTOMETER_CLUSTER_URI" to markerConfigValue,
                "OCTOMETER_MAIL_API_KEY" to markerConfigValue,
            ),
            userConfigFile = userFile,
        )

        val (_, events) = captureLogEvents { logStart(resolved) }
        val lines = events.joinToString("\n") { it.formattedMessage }

        assertTrue(lines.contains("clusterUri = set"), "clusterUri must print set: $lines")
        assertTrue(lines.contains("mailApiKey = set"), "mailApiKey must print set: $lines")
        assertFalse(lines.contains(markerConfigValue), "the log must never hold the real secret value")
    }

    // MAJOR 5 of the review of pull request #313 (issue #301, D58): D58
    // extends the log rule of D15 to the sign-in token, the session id,
    // and the mail address. ConnectionStringSentinelContainerTest.kt
    // needs Docker for its own marker flow (the Mongo container); this
    // guard needs none, so it sits here instead, the same choice as the
    // config-value sentinel above. It drives one real sign-in (the
    // file mail sender writes the link to a file, the same as the
    // end-to-end suite), one real confirm, and one real API call, each
    // with a marker value, then reads the whole captured log for each
    // of the three.
    @Test
    fun `a real sign-in, confirm, and API call never write the address, the token, or the session id to the log`() =
        testApplication {
            val markerEmail = "octomarkersigninuser4f9c@example.com"
            val config = devConfig()
            application { module(config) }
            startApplication()
            insertInviteRow(config.dataDir, markerEmail)

            val (marker, events) = captureLogEvents {
                val signInResponse = client.post("/api/signin") {
                    allowedHost()
                    header("Origin", "http://localhost:7431")
                    contentType(ContentType.Application.Json)
                    setBody("""{"email":"$markerEmail"}""")
                }
                assertEquals(HttpStatusCode.Accepted, signInResponse.status)

                val mailFile = File(config.mailDir).listFiles()?.maxByOrNull { it.lastModified() }
                    ?: error("The mail folder ${config.mailDir} holds no file.")
                val mailText = mailFile.readText()
                val token = Regex("token=([A-Za-z0-9_-]+)").find(mailText)?.groupValues?.get(1)
                    ?: error("The mail text carries no token:\n$mailText")

                val confirmResponse = client.post("/api/signin/confirm") {
                    allowedHost()
                    header("Origin", "http://localhost:7431")
                    contentType(ContentType.Application.Json)
                    setBody("""{"token":"$token"}""")
                }
                assertEquals(HttpStatusCode.NoContent, confirmResponse.status)
                val setCookie = confirmResponse.headers[HttpHeaders.SetCookie]
                    ?: error("The confirm answer carries no Set-Cookie header.")
                val sessionValue = Regex("octometer_session=([^;]+)").find(setCookie)?.groupValues?.get(1)
                    ?: error("The Set-Cookie header carries no session value: $setCookie")

                val meResponse = client.get("/api/me") {
                    allowedHost()
                    header(HttpHeaders.Cookie, "octometer_session=$sessionValue")
                }
                assertEquals(HttpStatusCode.OK, meResponse.status)

                Triple(markerEmail, token, sessionValue)
            }

            val (address, token, sessionValue) = marker
            val lines = events.joinToString("\n") { it.formattedMessage }

            assertFalse(lines.contains(address), "the log must never hold the mail address: $lines")
            assertFalse(lines.contains(token), "the log must never hold the sign-in token: $lines")
            assertFalse(lines.contains(sessionValue), "the log must never hold the session id: $lines")
        }

    // Issue #296, D51, D58, the correction of the brief review (m13):
    // the sentinel of the new key, through the same precedent as the
    // test above. clusterUri must be non-blank, else the create gives
    // 503 before it ever makes a key. This reads the plain secret key
    // from the 201 answer, then asserts the captured log holds none of
    // it: the secret key is random at each run, so no fixed marker
    // name is possible here.
    @Test
    fun `a real app registration with origins never writes the secret key to the log`() = testApplication {
        val config = devConfig(clusterUri = "mongodb://127.0.0.1:1/octometer_service")
        application { module(config) }
        startApplication()
        val cookie = sessionCookieValue(config.dataDir)

        val (secretKey, events) = captureLogEvents {
            val response = client.post("/api/apps") {
                allowedHost()
                withSession(cookie)
                header("Origin", "http://localhost:7431")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"sentinel-app","origins":["https://example.com"]}""")
            }
            assertEquals(HttpStatusCode.Created, response.status)
            Json.parseToJsonElement(response.bodyAsText()).jsonObject["secretKey"]!!.jsonPrimitive.content
        }

        val lines = events.joinToString("\n") { it.formattedMessage }
        assertFalse(lines.contains(secretKey), "the log must never hold the secret key: $lines")
    }

    // Issue #301, brief review M8: the default of mailSender is "file"
    // when publicUrl is blank or a loopback host, else "resend".
    @Test
    fun `the bundled default of mailSender is file with a blank publicUrl, and resend with a public host`() {
        val userFile = missingUserConfigFile()

        val blankPublicUrl = loadConfig(args = emptyArray(), env = emptyMap(), userConfigFile = userFile)
        val loopbackPublicUrl = loadConfig(
            args = arrayOf("-P:octometer.publicUrl=http://127.0.0.1:7431"),
            env = emptyMap(),
            userConfigFile = userFile,
        )
        val publicHost = loadConfig(
            args = arrayOf("-P:octometer.publicUrl=https://app.0ct0meter.com"),
            env = emptyMap(),
            userConfigFile = userFile,
        )

        assertEquals("file", blankPublicUrl.config.mailSender)
        assertEquals("file", loopbackPublicUrl.config.mailSender)
        assertEquals("resend", publicHost.config.mailSender)
    }

    @Test
    fun `an OCTOMETER_MAIL_SENDER value outside resend and file gives an InvalidConfigException`() {
        val userFile = missingUserConfigFile()

        assertFailsWith<InvalidConfigException> {
            loadConfig(
                args = emptyArray(),
                env = mapOf("OCTOMETER_MAIL_SENDER" to "carrier-pigeon"),
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

    // MAJOR 5 of the review of pull request #313: a raw JDBC connection
    // of its own, the same pattern as TestConfigs.kt's
    // sessionCookieValue helpers (WAL mode admits a second writer). The
    // wait loop covers the same lazy start of Ktor's testApplication
    // (the module, and so the migrations, run at the first request, not
    // at the `application { }` call).
    private fun insertInviteRow(dataDir: String, email: String) {
        val dbFile = File(dataDir, "octometer.db")
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!dbFile.isFile && System.nanoTime() < deadline) {
            Thread.sleep(20)
        }
        DriverManager.getConnection("jdbc:sqlite:" + dbFile.absolutePath).use { connection ->
            connection.createStatement().use { it.execute("PRAGMA busy_timeout=5000") }
            insertInvite(connection, email, System.currentTimeMillis())
        }
    }
}
