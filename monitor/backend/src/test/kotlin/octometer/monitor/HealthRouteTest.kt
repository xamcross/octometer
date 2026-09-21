package octometer.monitor

import io.ktor.client.request.get
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
}
