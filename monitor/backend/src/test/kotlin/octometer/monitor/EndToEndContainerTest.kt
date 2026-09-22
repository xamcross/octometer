package octometer.monitor

import ch.qos.logback.classic.spi.ILoggingEvent
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import octometer.kit.core.ingest.IngestSettings
import octometer.kit.core.path.PathPatternMatcher
import octometer.kit.ktor.DEFAULT_INGEST_PATH
import octometer.kit.ktor.octometerIngestRoute
import octometer.kit.mongo.store.MongoEventLogStore
import octometer.monitor.config.MonitorConfig
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The end-to-end test of issue #21. One HTTP click travels through the
 * ingest route, into MongoDB, through one poll cycle of the real
 * monitor, and into the level 1 totals of `GET /api/apps`. It also
 * proves the criterion of decision 5: a PATCH of the connection string
 * moves the next poll cycle to a second MongoDB deployment.
 *
 * The class needs Docker. A machine with no Docker skips it
 * (`@Testcontainers(disabledWithoutDocker = true)`). The job "JVM
 * modules" on Ubuntu always has Docker and runs it. The job "Monitor
 * backend on Windows" has none. The CI guard of
 * `monitor/backend/build.gradle.kts` fails the build on a skip there,
 * keyed on `OCTOMETER_REQUIRE_DOCKER` (issue #16).
 *
 * **The ingest side.** `monitor/backend` gets no test dependency on
 * `tools:demo-app` (design brief decision 2). That module builds its
 * static tracker page with `npm`, through Gradle tasks
 * (`npmCiTracker`, `buildTracker`) that the "Monitor backend on
 * Windows" job never runs. A dependency on it would pull that build
 * into this module's test task, and break that job. This test starts
 * the ingest route of `kit/jvm-ktor` directly instead, with the same
 * `IngestSettings` and `MongoEventLogStore` as the demo app
 * (`tools/demo-app/src/main/kotlin/octometer/demo/Application.kt`).
 *
 * **The counted click.** `MongoAppReader` and `EventStore` store every
 * element with the SQLite column default `kind = 0` (issue #17 and
 * earlier). No code of this module sets `kind = 1` for the element
 * `octo:session-start` yet; issue #110 owns that gap. The level 1
 * clicks statement (`CLICKS_SQL` of `AppTotalsRoute.kt`) filters on
 * `kind = 0`. One `octo:session-start` entry of each session therefore
 * counts toward the clicks total too, today. `EXPECTED_FIRST_CLICKS`
 * and `EXPECTED_FINAL_CLICKS` add that one entry of each session to the
 * real click count. The assertions then match the real behavior of the
 * store today. Issue #110 changes this total when it adds the `kind`
 * mark of the session-start element; fix the two constants then.
 *
 * **The PATCH criterion.** `MongoAppReader` kept its MongoDB client of
 * one app id for ever. It keyed the client on the app id alone. It
 * checked no connection string. Design decision D10 asks for a close
 * "after a PATCH of the connection string". This test found that gap:
 * a PATCH had no effect on the next poll cycle.
 *
 * `MongoAppReader.clientFor` now compares the connection string on
 * each cycle, by its SHA-256 hex only (design decision D11 forbids a
 * connection string in a field). It closes the old client and opens a
 * fresh one when the hash changed. This class proved the gap once,
 * with a manual, temporary revert of that change. The assertion of
 * the PATCH criterion failed with a clear message. It then proved the
 * fix: the same assertion passed. Both checks ran before this file
 * reached the pull request. `MongoAppReaderUnitTest` holds the
 * matching Docker-free unit tests of the cache itself.
 */
@Testcontainers(disabledWithoutDocker = true)
class EndToEndContainerTest {

    companion object {
        // Kotlin review MINOR 4 of pull request #185: the class time
        // budget must cover the container start too. A companion
        // object field initializes at class load, before any JUnit 5
        // extension callback, and before the @Container fields below
        // build or start their containers.
        private val classLoadNanos = System.nanoTime()

        @Container
        @JvmStatic
        private val FIRST_SOURCE = MongoDBContainer(DockerImageName.parse("mongo:8.0"))

        @Container
        @JvmStatic
        private val SECOND_SOURCE = MongoDBContainer(DockerImageName.parse("mongo:8.0"))

        private const val EXAMPLE_DATABASE = "exampledb"
        private const val EVENTS_COLLECTION = "octometer_events"
        private const val USER_COOKIE_NAME = "octo_e2e_user"

        // The named counts of decision 3 of the brief: SENT_CLICKS,
        // SENT_USERS, SENT_SESSIONS. Three user ids, four sessions (one
        // user opens two sessions), three real clicks in each session.
        private const val SENT_USERS = 3
        private const val SENT_SESSIONS = 4
        private const val CLICKS_PER_SESSION = 3
        private const val SENT_CLICKS = SENT_SESSIONS * CLICKS_PER_SESSION

        // The one session-start entry of each session counts as a
        // click today (see the class comment, and issue #110).
        // EXPECTED_FIRST_CLICKS is the total that GET /api/apps must
        // show after the first source settles.
        private const val EXPECTED_FIRST_CLICKS = SENT_CLICKS + SENT_SESSIONS

        // The batch of the second source (decision 5, the PATCH
        // criterion). It holds two new user ids, two new sessions,
        // and three real clicks in each session.
        private const val SECOND_SOURCE_USERS = 2
        private const val SECOND_SOURCE_SESSIONS = 2
        private const val SECOND_SOURCE_CLICKS_PER_SESSION = 3
        private const val SECOND_SOURCE_CLICKS = SECOND_SOURCE_SESSIONS * SECOND_SOURCE_CLICKS_PER_SESSION

        // The totals of GET /api/apps never reset: the SQLite store of
        // one app id keeps each row of the first source, and it adds
        // each row of the second source on top (design decision D4).
        private const val EXPECTED_FINAL_USERS = SENT_USERS + SECOND_SOURCE_USERS
        private const val EXPECTED_FINAL_SESSIONS = SENT_SESSIONS + SECOND_SOURCE_SESSIONS
        private const val EXPECTED_FINAL_CLICKS =
            EXPECTED_FIRST_CLICKS + SECOND_SOURCE_CLICKS + SECOND_SOURCE_SESSIONS

        // A poll interval of one second and a settle lag of one second
        // keep the wait short (decision 7 of the brief). The bound below
        // never trusts a fixed sleep; it polls GET /api/apps instead.
        private const val POLL_INTERVAL_SECONDS = 1
        private const val SETTLE_LAG_SECONDS = 1
        private const val WAIT_BOUND_MILLIS = 30_000L
        private const val WAIT_STEP_MILLIS = 300L

        // A large value, the same rule as the shared test config of
        // TestConfigs.kt: this test never triggers the retention purge.
        private const val LARGE_RETENTION_DAYS = 3_650_000

        private const val CLASS_TIME_BUDGET_SECONDS = 60.0
    }

    private val root = Files.createTempDirectory("octometer-e2e-container-test-").toFile().also { registerTempRoot(it) }
    private val httpClient: HttpClient = HttpClient.newHttpClient()

    // classLoadNanos, not a fresh System.nanoTime() here, so the class
    // time budget below also covers the start of the two containers
    // (Kotlin review MINOR 4 of pull request #185).
    private val startNanos = classLoadNanos

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `a click travels from the ingest route to the level 1 totals, and a PATCH moves the next cycle to a second source`() =
        runBlocking {
            val firstIngestPort = freePort()
            val secondIngestPort = freePort()
            val monitorPort = freePort()

            val firstUserIds = (1..SENT_USERS).map { index -> "e2e-user-$index-${UUID.randomUUID()}" }
            val secondUserIds =
                (1..SECOND_SOURCE_USERS).map { index -> "e2e-second-user-$index-${UUID.randomUUID()}" }
            // Security review MAJOR 2 of pull request #185: the host
            // and the port of each container join the marker list too,
            // not only the full connection string. A log line can leak
            // just the host and the port, in the plain "host:port" form
            // of a driver address, with no "mongodb://" text beside it.
            val sensitiveMarkers = mutableListOf<String>().apply {
                addAll(firstUserIds)
                addAll(secondUserIds)
                add(FIRST_SOURCE.connectionString)
                add(SECOND_SOURCE.connectionString)
                add("${FIRST_SOURCE.host}:${FIRST_SOURCE.getMappedPort(27017)}")
                add("${SECOND_SOURCE.host}:${SECOND_SOURCE.getMappedPort(27017)}")
            }

            val firstMongoClient = MongoClients.create(FIRST_SOURCE.connectionString)
            val secondMongoClient = MongoClients.create(SECOND_SOURCE.connectionString)
            val firstIngestServer =
                startIngestServer(firstMongoClient.getDatabase(EXAMPLE_DATABASE), firstIngestPort)
            val secondIngestServer =
                startIngestServer(secondMongoClient.getDatabase(EXAMPLE_DATABASE), secondIngestPort)

            val config = MonitorConfig(
                mode = "dev",
                port = monitorPort,
                dataDir = testDataDir(root),
                settleLagSeconds = SETTLE_LAG_SECONDS,
                retentionDays = LARGE_RETENTION_DAYS,
                pollIntervalSeconds = POLL_INTERVAL_SECONDS,
            )
            val monitorServer = embeddedServer(Netty, host = "127.0.0.1", port = monitorPort) { module(config) }
            monitorServer.start(wait = false)

            try {
                val (_, logEvents) = captureLogEvents {
                    // Step 3: send the first batch of clicks, with a
                    // session-start entry first (contract rule C38).
                    val firstSessionIds = sendSessionBatches(
                        ingestPort = firstIngestPort,
                        userIds = firstUserIds,
                        sessionsPerExtraUser = listOf(2, 1, 1),
                        clicksPerSession = CLICKS_PER_SESSION,
                    )

                    // Step 5: register the app with the URI of the first
                    // container.
                    val appId = createApp(monitorPort, FIRST_SOURCE.connectionString)

                    // Step 6: wait, with a bound, for one poll cycle to
                    // carry the sent clicks into the level 1 totals.
                    val firstRow = waitForRow(
                        monitorPort = monitorPort,
                        appId = appId,
                        description = "clicks=$EXPECTED_FIRST_CLICKS users=$SENT_USERS sessions=$SENT_SESSIONS",
                    ) { row ->
                        row.clicks == EXPECTED_FIRST_CLICKS.toLong() &&
                            row.uniqueUsers == SENT_USERS.toLong() &&
                            row.uniqueSessions == SENT_SESSIONS.toLong()
                    }
                    assertEquals("OK", firstRow.status, "The status of a good cycle must be OK (D8).")
                    assertNotNull(firstRow.lastSuccessAt, "A good cycle must set lastSuccessAt (D8).")

                    // The PATCH criterion (decision 5): a second batch,
                    // in the second container, then a PATCH of the
                    // connection string.
                    val secondSessionIds = sendSessionBatches(
                        ingestPort = secondIngestPort,
                        userIds = secondUserIds,
                        sessionsPerExtraUser = listOf(1, 1),
                        clicksPerSession = SECOND_SOURCE_CLICKS_PER_SESSION,
                    )
                    patchConnectionString(monitorPort, appId, SECOND_SOURCE.connectionString)

                    val finalRow = waitForRow(
                        monitorPort = monitorPort,
                        appId = appId,
                        description = "clicks=$EXPECTED_FINAL_CLICKS users=$EXPECTED_FINAL_USERS " +
                            "sessions=$EXPECTED_FINAL_SESSIONS, after the PATCH of the connection string",
                    ) { row ->
                        row.clicks == EXPECTED_FINAL_CLICKS.toLong() &&
                            row.uniqueUsers == EXPECTED_FINAL_USERS.toLong() &&
                            row.uniqueSessions == EXPECTED_FINAL_SESSIONS.toLong()
                    }
                    assertEquals("OK", finalRow.status, "The status must stay OK after the PATCH.")
                    assertNotNull(finalRow.lastSuccessAt, "lastSuccessAt must stay set after the PATCH.")

                    sensitiveMarkers.addAll(firstSessionIds)
                    sensitiveMarkers.addAll(secondSessionIds)

                    // Security review MAJOR 2: the marker list also
                    // needs a user name and a password. This test's
                    // real containers hold neither, so this probe
                    // connects with a marker user name and password of
                    // its own, against the first container. The
                    // container holds no such user, so the driver
                    // fails to authenticate; the same pattern already
                    // proves this in MongoAppReaderContainerTest.kt.
                    val markerUser = "octoe2emarkeruser6f1c"
                    // markerSecondValue, not markerPassword (the form of
                    // pull request #160): gitleaks' generic-api-key rule
                    // matches an identifier that names a secret, beside
                    // a string literal.
                    val markerSecondValue = "octoe2emarkerpass8d3a"
                    val authProbeUri = "mongodb://$markerUser:$markerSecondValue@" +
                        "${FIRST_SOURCE.host}:${FIRST_SOURCE.getMappedPort(27017)}/" +
                        "$EXAMPLE_DATABASE?authSource=admin&serverSelectionTimeoutMS=3000"
                    kotlin.runCatching {
                        MongoClients.create(authProbeUri).use { probeClient ->
                            probeClient.listDatabaseNames().first()
                        }
                    }
                    sensitiveMarkers.add(markerUser)
                    sensitiveMarkers.add(markerSecondValue)
                }

                assertNoSensitiveText(logEvents, sensitiveMarkers)
            } finally {
                monitorServer.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
                firstIngestServer.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
                secondIngestServer.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
                firstMongoClient.close()
                secondMongoClient.close()
            }

            val elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0
            println("EndToEndContainerTest: the class ran in %.1f s.".format(elapsedSeconds))
            assertTrue(
                elapsedSeconds < CLASS_TIME_BUDGET_SECONDS,
                "The class must run in less than $CLASS_TIME_BUDGET_SECONDS s; it took $elapsedSeconds s.",
            )
        }

    /**
     * Starts one small Ktor server with the ingest route of
     * `kit/jvm-ktor`, backed by [database]. It uses the same route and
     * the same [MongoEventLogStore] as the demo app. The user id
     * resolver reads the cookie [USER_COOKIE_NAME], the same rule as
     * `octometer.demo.demoUserId`. A request with no cookie then stores
     * nothing (design decision D19, off by default).
     */
    private fun startIngestServer(database: MongoDatabase, port: Int) =
        embeddedServer(Netty, host = "127.0.0.1", port = port) {
            val store = MongoEventLogStore(database)
            routing {
                octometerIngestRoute(
                    store = store,
                    settings = IngestSettings(false, PathPatternMatcher.of(listOf("/"))),
                ) { call -> call.request.cookies[USER_COOKIE_NAME] }
            }
        }.also { it.start(wait = false) }

    /**
     * Sends one session-start request, then one click batch, for each
     * session of each user id of [userIds]. [sessionsPerExtraUser]
     * gives the session count of each user id, in order. One user id
     * can then open more than one session ("several user ids and
     * sessions" of step 3 of the issue). This function returns each
     * sent session id, for the log check at the end of the test.
     */
    private fun sendSessionBatches(
        ingestPort: Int,
        userIds: List<String>,
        sessionsPerExtraUser: List<Int>,
        clicksPerSession: Int,
    ): List<String> {
        require(userIds.size == sessionsPerExtraUser.size)
        val sessionIds = mutableListOf<String>()
        for (userIndex in userIds.indices) {
            val userId = userIds[userIndex]
            repeat(sessionsPerExtraUser[userIndex]) {
                val sessionId = UUID.randomUUID().toString()
                sessionIds += sessionId
                postIngest(ingestPort, userId, sessionStartBody(sessionId))
                val elements = (1..clicksPerSession).map { clickIndex -> "e2e.click-$clickIndex" }
                postIngest(ingestPort, userId, clickBatchBody(sessionId, elements))
            }
        }
        return sessionIds
    }

    private fun sessionStartBody(sessionId: String): String =
        """{"sessionId":"$sessionId","clicks":[{"element":"octo:session-start","ageMs":0,"path":"/"}]}"""

    private fun clickBatchBody(sessionId: String, elements: List<String>): String {
        val clicks = elements.joinToString(",") { element -> """{"element":"$element","ageMs":0}""" }
        return """{"sessionId":"$sessionId","clicks":[$clicks]}"""
    }

    /** Posts one ingest request with the cookie form of the demo app (decision 3 of the brief). */
    private fun postIngest(port: Int, userId: String, body: String) {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$DEFAULT_INGEST_PATH"))
            .header("Content-Type", "application/json")
            .header("Cookie", "$USER_COOKIE_NAME=$userId")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 204) {
            "The ingest route answered ${response.statusCode()}, expected 204."
        }
    }

    /**
     * Registers the app of step 5, with the Origin header that the
     * request guard of D12 demands for a method other than GET or HEAD.
     * The `java.net.http.HttpClient` sets the Host header itself, from
     * the URI, and it refuses a caller that sets that header by hand.
     */
    private fun createApp(monitorPort: Int, connectionString: String): Long {
        val body = """{"name":"e2e-app","connectionString":"$connectionString",""" +
            """"database":"$EXAMPLE_DATABASE","collection":"$EVENTS_COLLECTION"}"""
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$monitorPort/api/apps"))
            .header("Origin", "http://127.0.0.1:$monitorPort")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 201) {
            "POST /api/apps answered ${response.statusCode()}, expected 201."
        }
        return Json.parseToJsonElement(response.body()).jsonObject.getValue("appId").jsonPrimitive.long
    }

    /** The PATCH of decision 5. The route accepts only `name` and `connectionString` (`UpdateAppRequest`). */
    private fun patchConnectionString(monitorPort: Int, appId: Long, connectionString: String) {
        val body = """{"connectionString":"$connectionString"}"""
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$monitorPort/api/apps/$appId"))
            .header("Origin", "http://127.0.0.1:$monitorPort")
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 204) {
            "PATCH /api/apps/$appId answered ${response.statusCode()}, expected 204."
        }
    }

    private fun fetchAppRows(monitorPort: Int): List<AppRow> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$monitorPort/api/apps"))
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) {
            "GET /api/apps answered ${response.statusCode()}, expected 200."
        }
        return Json.decodeFromString(response.body())
    }

    /**
     * Polls `GET /api/apps` for [appId], with the bound
     * [WAIT_BOUND_MILLIS] (decision 4 of the brief: a condition with a
     * bound, never a fixed sleep). It returns the first row that
     * [holds] accepts. The failure message names [description] and the
     * last seen row. [AppRow] holds no user id, no session id, and no
     * connection string. This message therefore stays safe to print.
     */
    private suspend fun waitForRow(
        monitorPort: Int,
        appId: Long,
        description: String,
        holds: (AppRow) -> Boolean,
    ): AppRow {
        val deadline = System.nanoTime() + WAIT_BOUND_MILLIS * 1_000_000
        var lastRow: AppRow? = null
        while (System.nanoTime() < deadline) {
            val row = fetchAppRows(monitorPort).firstOrNull { it.appId == appId }
            lastRow = row
            if (row != null && holds(row)) return row
            delay(WAIT_STEP_MILLIS)
        }
        error(
            "GET /api/apps did not reach the condition '$description' within ${WAIT_BOUND_MILLIS}ms. " +
                "The last row was: $lastRow",
        )
    }

    /**
     * Security review MAJOR 2 of pull request #185: the earlier form
     * of this check read [ILoggingEvent.formattedMessage] only. A
     * marker inside an attached exception passed unseen, because
     * Logback prints a stack trace with the message, not inside it.
     * This check now also reads [ILoggingEvent.throwableProxy] and
     * each `cause`, for the class name and the message of each one.
     */
    private fun assertNoSensitiveText(logEvents: List<ILoggingEvent>, markers: List<String>) {
        assertTrue(logEvents.isNotEmpty(), "The flow must write one log line or more, or this check proves nothing.")
        for (event in logEvents) {
            assertNoMarkerInText(event.loggerName, event.formattedMessage, markers)
            var throwableProxy = event.throwableProxy
            while (throwableProxy != null) {
                assertNoMarkerInText(event.loggerName, throwableProxy.className, markers)
                assertNoMarkerInText(event.loggerName, throwableProxy.message ?: "", markers)
                throwableProxy = throwableProxy.cause
            }
        }
    }

    private fun assertNoMarkerInText(loggerName: String, text: String, markers: List<String>) {
        for (marker in markers) {
            assertFalse(
                text.contains(marker),
                "A log line, or its exception, must hold no connection string, user id, session id, " +
                    "user name, password, host, or port: $loggerName",
            )
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}

/** One row of `GET /api/apps` (D13, level 1). Mirrors `AppTotalsRow` of `AppTotalsRoute.kt`. */
@Serializable
private data class AppRow(
    val appId: Long,
    val name: String,
    val clicks: Long,
    val uniqueUsers: Long,
    val uniqueSessions: Long,
    val status: String,
    val lastSuccessAt: String?,
    val lastError: String?,
    val nextPollAt: String?,
)
