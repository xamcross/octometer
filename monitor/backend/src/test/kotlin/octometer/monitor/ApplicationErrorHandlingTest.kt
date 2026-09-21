package octometer.monitor

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import octometer.monitor.registry.allowlistedSrvUri
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

    @Test
    fun `an unhandled exception gives a fixed JSON 500, with the security headers of issue 5`() = testApplication {
        val dataDir = testDataDir()
        // A file at the secrets folder path makes every secret write
        // throw, so the POST handler meets an exception that is not
        // SecretStoreUnavailableException, and it never catches that one.
        File(File(dataDir).parentFile, "secrets").writeText("not a directory")
        application { module(prodConfig(dataDir = dataDir)) }

        val response = client.post("/api/apps") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431")
            contentType(ContentType.Application.Json)
            setBody(
                """{"name":"demo","connectionString":"${allowlistedSrvUri()}",""" +
                    """"database":"db","collection":"octometer_events"}""",
            )
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val bodyText = response.bodyAsText()
        assertFalse(bodyText.contains(allowlistedSrvUri()))
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
