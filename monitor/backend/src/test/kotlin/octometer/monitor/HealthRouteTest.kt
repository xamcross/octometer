package octometer.monitor

import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthRouteTest {

    // The project version, from build.gradle.kts. A change to one value needs
    // a change to the other.
    private val projectVersion = "0.1.0"

    @Test
    fun `the health route answers with the version and the mode, and refreshSeconds is 5 in dev mode`() =
        testApplication {
            application { module(devConfig()) }

            val response = client.get("/api/health") { allowedHost() }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(projectVersion, body["version"]!!.jsonPrimitive.content)
            assertEquals("dev", body["mode"]!!.jsonPrimitive.content)
            assertEquals(5, body["refreshSeconds"]!!.jsonPrimitive.int)
        }

    @Test
    fun `refreshSeconds is 60 in prod mode`() = testApplication {
        application { module(prodConfig()) }

        val response = client.get("/api/health") { allowedHost() }

        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("prod", body["mode"]!!.jsonPrimitive.content)
        assertEquals(60, body["refreshSeconds"]!!.jsonPrimitive.int)
    }

    // Issue #59, step 5: the health route also gives the configured
    // retentionDays value, so the level 1 view can show it (issue #66).
    @Test
    fun `the health route answers with retentionDays`() = testApplication {
        application { module(devConfig(retentionDays = 30)) }

        val response = client.get("/api/health") { allowedHost() }

        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(30, body["retentionDays"]!!.jsonPrimitive.int)
    }

    // Correction round 1 of issue #38 (MINOR 2, security review): D12
    // names HEAD a safe method, the same as GET. AutoHeadResponse builds
    // the HEAD answer from the GET route.
    @Test
    fun `HEAD on the health route answers 200 with no body`() = testApplication {
        application { module(prodConfig()) }

        val response = client.head("/api/health") { allowedHost() }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("", response.bodyAsText())
    }
}
