package octometer.monitor

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthRouteTest {

    // The project version, from build.gradle.kts. A change to one value needs
    // a change to the other.
    private val projectVersion = "0.1.0"

    @Test
    fun `the health route answers with a version and a mode`() = testApplication {
        application { module() }

        val response = client.get("/api/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(projectVersion, body["version"]!!.jsonPrimitive.content)
        assertTrue(body["mode"]!!.jsonPrimitive.content.isNotBlank())
    }
}
