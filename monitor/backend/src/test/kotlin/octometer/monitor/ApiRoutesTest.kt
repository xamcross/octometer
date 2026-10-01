package octometer.monitor

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.sql.DriverManager
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import octometer.monitor.collect.AppKeyCache
import octometer.monitor.collect.CollectorRateLimiting
import octometer.monitor.collect.CollectorRuntime
import octometer.monitor.collect.CollectorStore
import octometer.monitor.collect.DAILY_EVENT_CAP
import octometer.monitor.collect.DailyEventCountStore
import octometer.monitor.collect.WriteStopGate
import octometer.monitor.collect.collectRoute
import octometer.monitor.store.SqliteDatabase

/**
 * Correction round 1 of issue #38 (MINOR 4, security review): the
 * earlier fallback of `apiRoutes` answered GET alone. A different
 * method on an unknown API path fell through with an empty body,
 * instead of the fixed JSON body of every other 404.
 *
 * MAJOR 4 of the review of pull request #327: this class also grows
 * with the collector cases (issue #297, D50), the owner's rule that a
 * correction round grows an existing test class, never a new one. Each
 * registration below uses the hosted path of `POST /api/apps` (an
 * origin list, no connection string), with a bogus `clusterUri` on the
 * loopback address: the registration itself touches SQLite only, and a
 * later write that the collector route attempts against that address
 * fails and is caught (D50: a write failure answers 204, never 500),
 * the same pattern as `AppRegistryRoutesTest`.
 */
class ApiRoutesTest {

    @Test
    fun `a POST on an unknown api path gets the fixed JSON body, not an empty one`() = testApplication {
        val config = prodConfig()
        application { module(config) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)

        val response = client.post("/api/does-not-exist") {
            allowedHost()
            header("Origin", "http://localhost:7431")
            withSession(session)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        val bodyText = response.bodyAsText()
        val errorText = Json.parseToJsonElement(bodyText).jsonObject["error"]!!.jsonPrimitive.content
        assertEquals("The route does not exist.", errorText)
    }

    // -- The collector route (issue #297, D50) ----------------------------

    @Test
    fun `POST collect with a non-json content type gets 415`() = testApplication {
        val config = devConfig(clusterUri = "mongodb://127.0.0.1:1/octometer_service")
        application { module(config) }
        startApplication()
        val (_, appKey, origin) = registerCollectorApp(this, config.dataDir)

        val response = client.post("/collect") {
            allowedHost()
            header(HttpHeaders.Origin, origin)
            header("X-Octometer-Key", appKey)
            contentType(ContentType.Text.Plain)
            setBody("not json")
        }

        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    }

    @Test
    fun `POST collect above the per-address rate limit gets 429`() = testApplication {
        application { module(prodConfig()) }
        startApplication()

        // MAJOR 1 of the review of pull request #327: the per-address
        // rate limit of OCTOMETER_ANON_REQ_PER_MIN (300) runs before
        // the app key lookup, keyed on the client address alone, with
        // no app ever found. This key has the exact shape of a real
        // key (isPlausibleAppKey), so it passes the cheap format check
        // and reaches the rate limiter; AppKeyCache.lookup then finds
        // no app for it (a fresh SQLite read, no Mongo touch at all),
        // the same low cost as a real flood of invented keys.
        val neverRegisteredAppKey = "A".repeat(43)
        var lastStatus: HttpStatusCode? = null
        repeat(301) {
            lastStatus = client.post("/collect") {
                allowedHost()
                header("X-Octometer-Key", neverRegisteredAppKey)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"sessionId":"00000000-0000-4000-8000-000000000000",""" +
                        """"clicks":[{"element":"a","ageMs":0}]}""",
                )
            }.status
        }

        assertEquals(HttpStatusCode.TooManyRequests, lastStatus)
    }

    @Test
    fun `OPTIONS collect answers the four CORS headers for a listed origin`() = testApplication {
        val config = devConfig(clusterUri = "mongodb://127.0.0.1:1/octometer_service")
        application { module(config) }
        startApplication()
        val (_, _, origin) = registerCollectorApp(this, config.dataDir)

        val response = client.options("/collect") { allowedHost(); header(HttpHeaders.Origin, origin) }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals(origin, response.headers[HttpHeaders.AccessControlAllowOrigin])
        assertEquals("Content-Type, X-Octometer-Key", response.headers[HttpHeaders.AccessControlAllowHeaders])
        assertEquals("POST", response.headers[HttpHeaders.AccessControlAllowMethods])
        assertEquals("86400", response.headers[HttpHeaders.AccessControlMaxAge])
    }

    @Test
    fun `OPTIONS collect answers no CORS header for an unlisted origin`() = testApplication {
        val config = devConfig(clusterUri = "mongodb://127.0.0.1:1/octometer_service")
        application { module(config) }
        startApplication()
        registerCollectorApp(this, config.dataDir)

        val response = client.options("/collect") { allowedHost(); header(HttpHeaders.Origin, "https://unlisted.example") }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
        assertNull(response.headers[HttpHeaders.AccessControlAllowHeaders])
    }

    @Test
    fun `POST collect past the daily cap of one app drops the batch, and droppedToday counts it`() = testApplication {
        val dataDir = testDataDir()
        val config = devConfig(dataDir = dataDir, clusterUri = "mongodb://127.0.0.1:1/octometer_service")
        application { module(config) }
        startApplication()
        val (appId, appKey, origin) = registerCollectorApp(this, dataDir)

        // DAILY_EVENT_CAP (20 000) minus one: the next single-event
        // request still passes; the one after that goes above the cap.
        seedDailyEventCount(dataDir, appId, count = 19_999)

        val underCap = oneCollectRequest(this, appKey, origin)
        assertEquals(HttpStatusCode.NoContent, underCap.status)

        val overCap = oneCollectRequest(this, appKey, origin)
        assertEquals(HttpStatusCode.NoContent, overCap.status)

        val session = sessionCookieValue(dataDir)
        val detail = client.get("/api/apps/$appId") { allowedHost(); withSession(session) }
        val droppedToday = Json.parseToJsonElement(detail.bodyAsText()).jsonObject["droppedToday"]!!.jsonPrimitive.long
        assertEquals(1L, droppedToday)
    }

    // The write stop (cluster storage at or above 450 MB) and the event
    // cap (50 000 events of one app's own Mongo collection) each need a
    // real dbStats or estimatedDocumentCount answer of a live MongoDB.
    // This correction round adds no Testcontainers class (the owner's
    // rule: an existing test class grows, no new one), so neither case
    // has a test here; the pull request text states this gap.

    // Issue #300 (design decision D54): the collector's own
    // acceptBodyUserId exception. A dropped value (an email address)
    // never answers 400; the batch is kept, the same status as a valid
    // id. The bogus clusterUri of registerCollectorApp makes the actual
    // Mongo write fail and get caught (D50: 204, never 500), so this
    // test proves the response code only, not the stored document.
    @Test
    fun `POST collect with a userId in the body gives 204 for a valid id and for a dropped one`() = testApplication {
        val config = devConfig(clusterUri = "mongodb://127.0.0.1:1/octometer_service")
        application { module(config) }
        startApplication()
        val (_, appKey, origin) = registerCollectorApp(this, config.dataDir)

        val validIdResponse = oneCollectRequestWithUserId(this, appKey, origin, "team/42")
        assertEquals(HttpStatusCode.NoContent, validIdResponse.status)

        val droppedIdResponse = oneCollectRequestWithUserId(this, appKey, origin, "someone@example.com")
        assertEquals(HttpStatusCode.NoContent, droppedIdResponse.status)
    }

    // MAJOR 3 of the review of pull request #333: the collector route
    // carries the user id through CollectRoute.kt and CollectorStore.kt
    // since issue #300. This class grows with the sentinel case,
    // instead of a new class (the owner's rule of a correction round).
    @Test
    fun `no captured log line holds the user id of a collect request`() = testApplication {
        val config = devConfig(clusterUri = "mongodb://127.0.0.1:1/octometer_service")
        application { module(config) }
        startApplication()
        val (_, appKey, origin) = registerCollectorApp(this, config.dataDir)
        val marker = "sentinel-collector-user-id-4d87"

        val (response, events) = captureLogEvents {
            oneCollectRequestWithUserId(this, appKey, origin, marker)
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        for (event in events) {
            assertFalse(event.formattedMessage.contains(marker), "a log line held the user id: ${event.formattedMessage}")
        }
    }

    // MAJOR 1 of the review of pull request #333: a dropped body user
    // id reaches the dev-only counter route, beside the request
    // counters that MAJOR 4 of the review of pull request #327 added.
    @Test
    fun `GET dev collector-counters reports a dropped body user id`() = testApplication {
        val config = devConfig(clusterUri = "mongodb://127.0.0.1:1/octometer_service")
        application { module(config) }
        startApplication()
        val (appId, appKey, origin) = registerCollectorApp(this, config.dataDir)

        oneCollectRequestWithUserId(this, appKey, origin, "someone@example.com")

        val session = sessionCookieValue(config.dataDir)
        val response = client.get("/api/dev/collector-counters") { allowedHost(); withSession(session) }
        val drops = Json.parseToJsonElement(response.bodyAsText()).jsonObject["droppedBodyUserIds"]!!.jsonArray
        val ownEntry = drops.first { it.jsonObject["appId"]!!.jsonPrimitive.long == appId }

        assertEquals(1L, ownEntry.jsonObject["count"]!!.jsonPrimitive.long)
    }

    // MAJOR 5 of the review of pull request #333: CollectRoute must
    // pick the limiter on the batch's own userId. This test builds its
    // own CollectorRuntime, with a direct, one-call exhaustion of the
    // anonymous global cap of design decision D43 (a real flood of
    // anonymous requests cannot reach that cap inside one fast test,
    // because the per-minute entry cap of D43 is lower, and always
    // triggers first). An identified batch must still pass.
    @Test
    fun `an identified batch above the anonymous daily cap of D43 still answers 204 and stores`() {
        val dataDir = testDataDir()
        var appId = 0L
        lateinit var appKey: String
        lateinit var origin: String
        testApplication {
            val config = devConfig(dataDir = dataDir, clusterUri = "mongodb://127.0.0.1:1/octometer_service")
            application { module(config) }
            startApplication()
            val (registeredAppId, registeredAppKey, registeredOrigin) = registerCollectorApp(this, dataDir)
            appId = registeredAppId
            appKey = registeredAppKey
            origin = registeredOrigin
        }

        val database = SqliteDatabase.open(dataDir)
        val dailyEventCountStore = DailyEventCountStore(database)
        val rateLimiting = CollectorRateLimiting(Clock.systemUTC())
        // One direct call, with a key no real request ever uses,
        // exhausts the global counter of the anonymous cap for this
        // app. An anonymous batch of this app would now always drop.
        rateLimiting.dailyCapOf(appId).check("exhaust-the-global-cap-key", DAILY_EVENT_CAP.toInt())
        val runtime = CollectorRuntime(
            appKeyCache = AppKeyCache(database),
            store = CollectorStore(clientFactory = { throw IllegalStateException("no real cluster in this test") }),
            dailyEventCountStore = dailyEventCountStore,
            writeStopGate = WriteStopGate(database, client = { null }),
            rateLimiting = rateLimiting,
            clientIpHeaderName = "",
        )

        lateinit var status: HttpStatusCode
        testApplication {
            application { routing { collectRoute(runtime) } }
            status = oneCollectRequestWithUserId(this, appKey, origin, "user-above-the-anonymous-cap").status
        }

        assertEquals(HttpStatusCode.NoContent, status)
        assertEquals(0L, dailyEventCountStore.droppedToday(appId), "the identified batch must never count as a D43 drop")
        runBlocking {
            assertEquals(1L, dailyEventCountStore.todayCount(appId), "the identified batch must reach the store step")
        }
        database.close()
    }

    // One origin list, one session, one app key: the shared registration
    // of every test above.
    private suspend fun registerCollectorApp(builder: ApplicationTestBuilder, dataDir: String): Triple<Long, String, String> {
        val origin = "https://collector-probe.example"
        val session = sessionCookieValue(dataDir)
        val created = builder.client.post("/api/apps") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431")
            contentType(ContentType.Application.Json)
            withSession(session)
            setBody("""{"name":"collector-probe","origins":["$origin"]}""")
        }
        val body = Json.parseToJsonElement(created.bodyAsText()).jsonObject
        val appId = body["appId"]!!.jsonPrimitive.long
        val appKey = body["appKey"]!!.jsonPrimitive.content
        return Triple(appId, appKey, origin)
    }

    private suspend fun oneCollectRequest(builder: ApplicationTestBuilder, appKey: String, origin: String): HttpResponse =
        builder.client.post("/collect") {
            allowedHost()
            header(HttpHeaders.Origin, origin)
            header("X-Octometer-Key", appKey)
            contentType(ContentType.Application.Json)
            setBody(
                """{"sessionId":"00000000-0000-4000-8000-000000000000",""" +
                    """"clicks":[{"element":"a","ageMs":0}]}""",
            )
        }

    /** The same request as [oneCollectRequest], with a top-level `userId` field (issue #300). */
    private suspend fun oneCollectRequestWithUserId(
        builder: ApplicationTestBuilder,
        appKey: String,
        origin: String,
        userId: String,
    ): HttpResponse =
        builder.client.post("/collect") {
            allowedHost()
            header(HttpHeaders.Origin, origin)
            header("X-Octometer-Key", appKey)
            contentType(ContentType.Application.Json)
            setBody(
                """{"sessionId":"00000000-0000-4000-8000-000000000001",""" +
                    """"clicks":[{"element":"a","ageMs":0}],"userId":"$userId"}""",
            )
        }
}

/**
 * Seeds `daily_event_count` of [appId] for today (UTC) at [count], the
 * same column pair as `DailyEventCountStore` (migration
 * `008_route_patterns_and_daily_count.sql`). A test this way reaches the
 * cap with one request, instead of a slow loop of thousands.
 */
private fun seedDailyEventCount(dataDir: String, appId: Long, count: Long) {
    val day = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC).format(Clock.systemUTC().instant())
    val url = "jdbc:sqlite:" + File(dataDir, "octometer.db").absolutePath
    DriverManager.getConnection(url).use { connection ->
        connection.prepareStatement(
            "INSERT INTO daily_event_count (app_id, day, count) VALUES (?, ?, ?)",
        ).use { insert ->
            insert.setLong(1, appId)
            insert.setString(2, day)
            insert.setLong(3, count)
            insert.executeUpdate()
        }
    }
}
