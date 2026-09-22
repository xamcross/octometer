package octometer.monitor

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Correction round 1 of issue #38 (MINOR 4, security review): the
 * earlier fallback of `apiRoutes` answered GET alone. A different
 * method on an unknown API path fell through with an empty body,
 * instead of the fixed JSON body of every other 404.
 */
class ApiRoutesTest {

    @Test
    fun `a POST on an unknown api path gets the fixed JSON body, not an empty one`() = testApplication {
        application { module(prodConfig()) }

        val response = client.post("/api/does-not-exist") {
            allowedHost()
            header("Origin", "http://localhost:7431")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        val bodyText = response.bodyAsText()
        val errorText = Json.parseToJsonElement(bodyText).jsonObject["error"]!!.jsonPrimitive.content
        assertEquals("The route does not exist.", errorText)
    }
}
