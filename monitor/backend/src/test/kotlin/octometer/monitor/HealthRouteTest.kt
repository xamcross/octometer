package octometer.monitor

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthRouteTest {

    @Test
    fun `the health route answers with a version and a mode`() = testApplication {
        application { module() }

        val response = client.get("/api/health")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["version"]!!.jsonPrimitive.content.isNotBlank())
        assertTrue(body["mode"]!!.jsonPrimitive.content.isNotBlank())
    }
}
