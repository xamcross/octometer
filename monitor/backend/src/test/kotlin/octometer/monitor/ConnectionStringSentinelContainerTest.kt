package octometer.monitor

import ch.qos.logback.classic.spi.ILoggingEvent
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import octometer.kit.core.ingest.IngestEvent
import octometer.kit.mongo.store.MongoEventLogStore
import octometer.monitor.config.MonitorConfig
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The systematic proof of issue #31 (design decisions D11, D15): the
 * three gaps of the maintainer's decision 1 are closed, and one
 * sentinel test covers each of them together, through the real
 * scheduler ([octometer.monitor.poll.PollScheduler]) and the real
 * reader ([octometer.monitor.mongo.MongoAppReader]), not a direct call
 * of either one.
 *
 * One app registers with a marker user name ([MARKER_CONNECTION_USER])
 * and a marker second value ([MARKER_SECOND_VALUE], the password) in
 * its connection string. Three real poll cycles then run in order:
 * 1. The registered connection string, with the wrong password. The
 *    real container holds no such user, so the driver's own SASL
 *    handshake fails, the same client-side failure as
 *    `MongoAppReaderContainerTest`'s "a wrong password gives the
 *    status UNAUTHORIZED" (that test stays; its KDoc names this class
 *    too, so a reader finds the direct-call form and this real-flow
 *    form together).
 * 2. A PATCH to a wrong host: a loopback address with no server
 *    behind it. D11 accepts a plain `mongodb://` URI for a loopback
 *    host only, so this test keeps the host and changes only the
 *    port, to a free one with nothing bound to it.
 * 3. A PATCH to the real container, with three events already stored
 *    under a marker user id ([MARKER_USER_ID]). The good poll must
 *    carry them into the level 1, level 2, and level 3 routes.
 *
 * The test then searches every captured log line, with its whole
 * throwable chain ([assertNoMarkerInLogs]), the `lastError` value that
 * each of the three cycles wrote, and the body of `GET /api/apps`, the
 * users route, and the elements route, for each marker. The user name
 * and the password marker must reach no log line and no `lastError`.
 * The password marker must reach no API response either. The user id
 * marker must reach no log line and no `lastError`, but it must reach
 * the users route and the elements route: an app's own data still
 * flows, only its secret does not.
 */
@Testcontainers(disabledWithoutDocker = true)
class ConnectionStringSentinelContainerTest {

    companion object {
        // Kotlin review MINOR 4 of pull request #185 (see
        // EndToEndContainerTest): the class time budget below must
        // cover the container start too, thus this reads the clock
        // before the @Container field starts it.
        private val classLoadNanos = System.nanoTime()

        @Container
        @JvmStatic
        private val MONGO = MongoDBContainer(DockerImageName.parse("mongo:8.0"))

        private const val EXAMPLE_DATABASE = "exampledb"
        private const val EVENTS_COLLECTION = "octometer_events"

        // The scheme and the "@" join in separate literals (the same
        // form as MongoAppReaderContainerTest's "a wrong password"
        // test), so the built string never sits as one contiguous
        // credential pattern in the source text that the secret scan
        // reads.
        private const val MARKER_CONNECTION_USER = "octomarkerconnuser6f2a"

        // markerSecondValue, not markerPassword: gitleaks' generic-api-key
        // rule matches an identifier that names a secret beside a string
        // literal (the implementer rule of the secret scan).
        private const val MARKER_SECOND_VALUE = "octomarkerconnpass9d4e"

        private const val MARKER_USER_ID = "octomarkereventuser3c71"

        /** The element name prefix of each good sentinel event, for the elements route check. */
        private const val SENTINEL_ELEMENT_PREFIX = "octo.sentinel-click"

        // Merge correction of pull request #211: issue #207 added the
        // wake rule of design decision D7. It compares the real clock
        // with the planned tick time, and a gap above two poll
        // intervals starts a 15-second wait. A poll interval of 1
        // second gave a threshold of 2 seconds, too close to ordinary
        // test overhead (a container start, a JVM warm-up). 5 seconds
        // gives a threshold of 10 seconds, well above that overhead.
        private const val POLL_INTERVAL_SECONDS = 5
        private const val SETTLE_LAG_SECONDS = 1

        // A large value, the same rule as TestConfigs.kt: this test
        // never triggers the retention purge.
        private const val LARGE_RETENTION_DAYS = 3_650_000

        private const val WAIT_STEP_MILLIS = 300L

        // Poll 1 (a wrong password) fails fast: a client-side SASL
        // rejection, with no network wait.
        private const val AUTH_FAILURE_WAIT_BOUND_MILLIS = 20_000L

        // Poll 2 (a wrong host) waits for the backoff delay after poll
        // 1 (about one poll interval), then the driver's own server
        // selection timeout of MongoAppReader's defaultMongoClient (10
        // seconds, SERVER_SELECTION_TIMEOUT_MS).
        private const val HOST_FAILURE_WAIT_BOUND_MILLIS = 40_000L

        // Poll 3 waits for the backoff delay after poll 2 (about two
        // poll intervals), then a fast, good poll cycle.
        private const val SUCCESS_WAIT_BOUND_MILLIS = 30_000L

        private const val CLASS_TIME_BUDGET_SECONDS = 120.0
    }

    private val root = Files.createTempDirectory("octometer-sentinel-container-test-").toFile().also { registerTempRoot(it) }
    private val httpClient: HttpClient = HttpClient.newHttpClient()
    private val startNanos = classLoadNanos

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `three real poll cycles keep the two connection-string sentinels out of every log line and lastError, and the password sentinel out of every API response`() =
        runBlocking {
            val monitorPort = freePort()
            val goodMongoClient: MongoClient = MongoClients.create(MONGO.connectionString)
            val observedLastErrors = mutableListOf<String?>()

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
                val (responses, logEvents) = captureLogEvents {
                    // The three sentinel-carrying events sit in the good
                    // source from the start. Two failed poll cycles run
                    // before the good one reaches this data, so it is
                    // well past the settle lag by then (no fixed sleep
                    // needed on top, decision 4 of EndToEndContainerTest's
                    // brief).
                    val store = MongoEventLogStore(goodMongoClient.getDatabase(EXAMPLE_DATABASE))
                    val events = (1..3).map { index ->
                        IngestEvent(UUID.randomUUID().toString(), "$SENTINEL_ELEMENT_PREFIX-$index", Instant.now())
                    }
                    store.append(events, MARKER_USER_ID)

                    // Poll 1: the app registers with the marker user name
                    // and the marker second value in its connection
                    // string (issue #31, step 4), against the real
                    // container's host and port, with no such user.
                    val badPasswordUri = "mongodb" + "://" + MARKER_CONNECTION_USER + ":" + MARKER_SECOND_VALUE +
                        "@" + MONGO.host + ":" + MONGO.getMappedPort(27017) + "/" + EXAMPLE_DATABASE +
                        "?authSource=admin"
                    val appId = createApp(httpClient, monitorPort, badPasswordUri, EXAMPLE_DATABASE, EVENTS_COLLECTION)

                    val afterAuthFailure = waitForStatus(
                        httpClient,
                        monitorPort,
                        appId,
                        "UNAUTHORIZED",
                        AUTH_FAILURE_WAIT_BOUND_MILLIS,
                    )
                    observedLastErrors += afterAuthFailure.lastError

                    // Poll 2: a wrong host. Nothing listens on this free
                    // port of the loopback address.
                    val deadPort = freePort()
                    val wrongHostUri = "mongodb" + "://" + "127.0.0.1:$deadPort/" + EXAMPLE_DATABASE
                    patchConnectionString(httpClient, monitorPort, appId, wrongHostUri)
                    val afterHostFailure = waitForStatus(
                        httpClient,
                        monitorPort,
                        appId,
                        "UNREACHABLE",
                        HOST_FAILURE_WAIT_BOUND_MILLIS,
                    )
                    observedLastErrors += afterHostFailure.lastError

                    // Poll 3: the good source, with the marker-user
                    // events already stored there.
                    patchConnectionString(httpClient, monitorPort, appId, MONGO.connectionString)
                    val afterSuccess = waitForStatus(httpClient, monitorPort, appId, "OK", SUCCESS_WAIT_BOUND_MILLIS)
                    observedLastErrors += afterSuccess.lastError

                    SentinelResponses(
                        appId = appId,
                        appsBody = fetchBody(httpClient, "http://127.0.0.1:$monitorPort/api/apps"),
                        usersBody = fetchBody(httpClient, "http://127.0.0.1:$monitorPort/api/apps/$appId/users"),
                        elementsBody = fetchBody(
                            httpClient,
                            "http://127.0.0.1:$monitorPort/api/apps/$appId/elements?userId=$MARKER_USER_ID",
                        ),
                        // Merge correction of pull request #211: issue
                        // #112 added this route after the first merge
                        // of this branch. Its statement filters on
                        // kind = 1 (a session start). Each good
                        // sentinel event of this test is a plain click
                        // (kind 0), so this route gives an empty row
                        // list for this app. The check below still
                        // proves that its body holds neither marker.
                        firstPagesBody = fetchBody(
                            httpClient,
                            "http://127.0.0.1:$monitorPort/api/apps/$appId/first-pages",
                        ),
                    )
                }

                assertNoMarkerInLogs(logEvents, MARKER_CONNECTION_USER)
                assertNoMarkerInLogs(logEvents, MARKER_SECOND_VALUE)
                assertNoMarkerInLogs(logEvents, MARKER_USER_ID)

                for (lastError in observedLastErrors) {
                    if (lastError == null) continue
                    assertFalse(lastError.contains(MARKER_CONNECTION_USER), "lastError held the user name: $lastError")
                    assertFalse(lastError.contains(MARKER_SECOND_VALUE), "lastError held the password: $lastError")
                    assertFalse(lastError.contains(MARKER_USER_ID), "lastError held the event user id: $lastError")
                }

                // The password sentinel must reach no API response
                // (D11: the API never returns a connection string).
                assertFalse(responses.appsBody.contains(MARKER_SECOND_VALUE), "GET /api/apps held the password")
                assertFalse(responses.usersBody.contains(MARKER_SECOND_VALUE), "the users route held the password")
                assertFalse(responses.elementsBody.contains(MARKER_SECOND_VALUE), "the elements route held the password")
                assertFalse(responses.firstPagesBody.contains(MARKER_SECOND_VALUE), "the first-pages route held the password")
                assertFalse(responses.appsBody.contains(MARKER_CONNECTION_USER), "GET /api/apps held the user name")
                assertFalse(responses.usersBody.contains(MARKER_CONNECTION_USER), "the users route held the user name")
                assertFalse(responses.elementsBody.contains(MARKER_CONNECTION_USER), "the elements route held the user name")
                assertFalse(responses.firstPagesBody.contains(MARKER_CONNECTION_USER), "the first-pages route held the user name")

                // The good events must still reach the app's own data
                // routes (D13), or this test would pass by accident,
                // with the good poll never having run at all (lesson 1
                // of the backend brief: a test that cannot fail is a
                // defect).
                assertTrue(responses.usersBody.contains(MARKER_USER_ID), "the users route must show the marker user id")
                // The elements route filters by userId as a query
                // parameter; the response never echoes that value back
                // (D11, D15). The element name of the good events proves
                // the good poll fed this route instead.
                assertTrue(
                    responses.elementsBody.contains(SENTINEL_ELEMENT_PREFIX),
                    "the elements route must show a sentinel element of the marker user id",
                )
            } finally {
                monitorServer.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
                goodMongoClient.close()
            }

            val elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0
            println("ConnectionStringSentinelContainerTest: the class ran in %.1f s.".format(elapsedSeconds))
            assertTrue(
                elapsedSeconds < CLASS_TIME_BUDGET_SECONDS,
                "The class must run in less than $CLASS_TIME_BUDGET_SECONDS s; it took $elapsedSeconds s.",
            )
        }

    /**
     * Security review MAJOR 2 of pull request #185 (see
     * EndToEndContainerTest): a marker inside an attached exception
     * passes unseen when a check reads only [ILoggingEvent.formattedMessage].
     * This reads [ILoggingEvent.throwableProxy] and each `cause` too,
     * because Logback prints a stack trace with the message, not
     * inside it.
     */
    private fun assertNoMarkerInLogs(logEvents: List<ILoggingEvent>, marker: String) {
        assertTrue(logEvents.isNotEmpty(), "the capture read no log event")
        for (event in logEvents) {
            assertFalse(event.formattedMessage.contains(marker), "a log line held the marker: ${event.formattedMessage}")
            var throwableProxy = event.throwableProxy
            while (throwableProxy != null) {
                assertFalse(throwableProxy.className.contains(marker), "an exception class name held the marker")
                assertFalse(
                    throwableProxy.message?.contains(marker) == true,
                    "an exception message held the marker: ${throwableProxy.message}",
                )
                throwableProxy = throwableProxy.cause
            }
        }
    }

    private fun createApp(
        client: HttpClient,
        monitorPort: Int,
        connectionString: String,
        database: String,
        collection: String,
    ): Long {
        val body = """{"name":"sentinel-app","connectionString":"$connectionString",""" +
            """"database":"$database","collection":"$collection"}"""
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$monitorPort/api/apps"))
            .header("Origin", "http://127.0.0.1:$monitorPort")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 201) {
            "POST /api/apps answered ${response.statusCode()}, expected 201."
        }
        return Json.parseToJsonElement(response.body()).jsonObject.getValue("appId").jsonPrimitive.long
    }

    private fun patchConnectionString(client: HttpClient, monitorPort: Int, appId: Long, connectionString: String) {
        val body = """{"connectionString":"$connectionString"}"""
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$monitorPort/api/apps/$appId"))
            .header("Origin", "http://127.0.0.1:$monitorPort")
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 204) {
            "PATCH /api/apps/$appId answered ${response.statusCode()}, expected 204."
        }
    }

    private fun fetchAppRows(client: HttpClient, monitorPort: Int): List<SentinelAppRow> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$monitorPort/api/apps")).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) {
            "GET /api/apps answered ${response.statusCode()}, expected 200."
        }
        return Json.decodeFromString(response.body())
    }

    private fun fetchBody(client: HttpClient, url: String): String {
        val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) {
            "GET $url answered ${response.statusCode()}, expected 200."
        }
        return response.body()
    }

    /**
     * Polls `GET /api/apps` for [appId], with the bound [boundMillis]
     * (never a fixed sleep, the same rule as EndToEndContainerTest's
     * `waitForRow`). It returns the first row whose `status` equals
     * [expectedStatus]. [SentinelAppRow] holds no connection string, so
     * the failure message stays safe to print.
     */
    private suspend fun waitForStatus(
        client: HttpClient,
        monitorPort: Int,
        appId: Long,
        expectedStatus: String,
        boundMillis: Long,
    ): SentinelAppRow {
        val deadline = System.nanoTime() + boundMillis * 1_000_000
        var lastRow: SentinelAppRow? = null
        while (System.nanoTime() < deadline) {
            val row = fetchAppRows(client, monitorPort).firstOrNull { it.appId == appId }
            lastRow = row
            if (row != null && row.status == expectedStatus) return row
            delay(WAIT_STEP_MILLIS)
        }
        error(
            "GET /api/apps did not reach the status '$expectedStatus' within ${boundMillis}ms. " +
                "The last row was: $lastRow",
        )
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}

/** The four API bodies of one test run, gathered inside the log capture. */
private data class SentinelResponses(
    val appId: Long,
    val appsBody: String,
    val usersBody: String,
    val elementsBody: String,
    val firstPagesBody: String,
)

/** One row of `GET /api/apps` (D13, level 1). Mirrors `AppTotalsRow` of `AppTotalsRoute.kt`. */
@Serializable
private data class SentinelAppRow(
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
