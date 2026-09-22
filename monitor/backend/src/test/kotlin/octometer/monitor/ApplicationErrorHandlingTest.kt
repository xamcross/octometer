package octometer.monitor

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * MAJOR 6 of the Ktor review: a failure that leaves a route handler must
 * never reach the default Ktor error page, because that page can print
 * the request and the stack trace. StatusPages gives a fixed JSON body
 * instead, and the security headers of issue #5 must stay on it too.
 */
class ApplicationErrorHandlingTest {

    // The old setup put a plain file at the secrets folder path. MINOR 2
    // of the third security review closed that hole: writeAll() now
    // throws SecretStoreUnavailableException there, and the route maps
    // it to 503. This probe route throws a plain exception instead, so
    // the test still proves the general catch-all path of StatusPages.
    @Test
    fun `an unhandled exception gives a fixed JSON 500, with the security headers of issue 5`() = testApplication {
        val marker = "fake-failure-4b7e21"
        application {
            module(prodConfig(dataDir = testDataDir()))
            routing {
                get("/api/probe/failure") {
                    throw IllegalStateException(marker)
                }
            }
        }

        val response = client.get("/api/probe/failure") { allowedHost() }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val bodyText = response.bodyAsText()
        assertFalse(bodyText.contains(marker))
        assertNotNull(Json.parseToJsonElement(bodyText).jsonObject["error"])
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertNotNull(response.headers["Content-Security-Policy"])
    }

    // MAJOR 2 of the second security review, and MAJOR 2 of the second
    // Ktor review: kotlinx.coroutines.CancellationException is a type
    // alias of java.util.concurrent.CancellationException. A route that
    // throws the Java class, with the call still active, must still get
    // the fixed JSON body, and never the default Ktor error page.
    @Test
    fun `a Java cancellation from a route gives the fixed JSON 500, and the marker text is in no answer`() =
        testApplication {
            val marker = "fake-cancellation-8f2c1d"
            application {
                module(prodConfig(dataDir = testDataDir()))
                routing {
                    get("/probe/cancellation") {
                        throw java.util.concurrent.CancellationException(marker)
                    }
                }
            }

            val response = client.get("/probe/cancellation") { allowedHost() }

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            val bodyText = response.bodyAsText()
            assertFalse(bodyText.contains(marker))
            assertNotNull(Json.parseToJsonElement(bodyText).jsonObject["error"])
        }
}
