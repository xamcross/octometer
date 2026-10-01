package octometer.monitor

import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Issue #301, D52: /api/health now needs a session, the same as every
// other /api/ route; each test below reads the config's own dataDir to
// seed one, through sessionCookieValue. /healthz sits outside /api/
// and needs none.
class HealthRouteTest {

    // The project version, from build.gradle.kts. A change to one value needs
    // a change to the other.
    private val projectVersion = "0.1.0"

    @Test
    fun `the health route answers with the version and the mode, and refreshSeconds is 5 in dev mode`() =
        testApplication {
            val config = devConfig()
            application { module(config) }
            startApplication()
            val session = sessionCookieValue(config.dataDir)

            val response = client.get("/api/health") { allowedHost(); withSession(session) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(projectVersion, body["version"]!!.jsonPrimitive.content)
            assertEquals("dev", body["mode"]!!.jsonPrimitive.content)
            assertEquals(5, body["refreshSeconds"]!!.jsonPrimitive.int)
        }

    @Test
    fun `refreshSeconds is 60 in prod mode`() = testApplication {
        val config = prodConfig()
        application { module(config) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)

        val response = client.get("/api/health") { allowedHost(); withSession(session) }

        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("prod", body["mode"]!!.jsonPrimitive.content)
        assertEquals(60, body["refreshSeconds"]!!.jsonPrimitive.int)
    }

    // Issue #59, step 5: the health route also gives the configured
    // retentionDays value, so the level 1 view can show it (issue #66).
    @Test
    fun `the health route answers with retentionDays`() = testApplication {
        val config = devConfig(retentionDays = 30)
        application { module(config) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)

        val response = client.get("/api/health") { allowedHost(); withSession(session) }

        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(30, body["retentionDays"]!!.jsonPrimitive.int)
    }

    // Issue #17, decision 6: refreshSeconds now comes from the one
    // config key pollIntervalSeconds, not from a second, duplicate value
    // of Mode. A config with pollIntervalSeconds = 7 must give
    // refreshSeconds = 7, in each mode.
    @Test
    fun `refreshSeconds follows the configured pollIntervalSeconds, for example 7`() = testApplication {
        val config = devConfig(pollIntervalSeconds = 7)
        application { module(config) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)

        val response = client.get("/api/health") { allowedHost(); withSession(session) }

        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(7, body["refreshSeconds"]!!.jsonPrimitive.int)
    }

    // Correction round 1 of issue #38 (MINOR 2, security review): D12
    // names HEAD a safe method, the same as GET. AutoHeadResponse builds
    // the HEAD answer from the GET route.
    @Test
    fun `HEAD on the health route answers 200 with no body`() = testApplication {
        val config = prodConfig()
        application { module(config) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)

        val response = client.head("/api/health") { allowedHost(); withSession(session) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("", response.bodyAsText())
    }

    // Decision 5 of issue #58: the health route also gives databaseBytes
    // (octometer.db plus octometer.db-wal) and freeDiskBytes. The store
    // open call already ran each migration by the time this route
    // answers (a real write on the writer thread), so databaseBytes is
    // above 0 with no extra write from this test.
    @Test
    fun `the health route answers with databaseBytes above 0 after the migrations, and freeDiskBytes above 0`() =
        testApplication {
            val config = devConfig()
            application { module(config) }
            startApplication()
            val session = sessionCookieValue(config.dataDir)

            val response = client.get("/api/health") { allowedHost(); withSession(session) }

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertTrue(body["databaseBytes"]!!.jsonPrimitive.long > 0, "the migrations already wrote octometer.db")
            assertTrue(body["freeDiskBytes"]!!.jsonPrimitive.long > 0, "a real disk has free space above 0")
        }

    // D56 (issue #295, issue #297): the health route answers with
    // storeState "ok", writeStopped a boolean (false, with no
    // clusterUri and no app in this test), and storageSizeBytes and
    // clusterCheckState each still null (a later issue fills them).
    @Test
    fun `the health route answers with storeState ok, writeStopped false, and the other D56 fields still null`() = testApplication {
        val config = devConfig()
        application { module(config) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)

        val response = client.get("/api/health") { allowedHost(); withSession(session) }

        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("ok", body["storeState"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["storageSizeBytes"])
        assertEquals(false, body["writeStopped"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, body["clusterCheckState"])
    }

    // D56: the process-and-store check, with no other content and no
    // cluster call. /healthz sits outside /api/, so it needs no session
    // (issue #301).
    @Test
    fun `healthz answers 200 with the plain text body ok`() = testApplication {
        application { module(devConfig()) }

        val response = client.get("/healthz") { allowedHost() }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
        assertEquals(ContentType.Text.Plain, response.contentType()?.withoutParameters())
    }
}
