package octometer.demo

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import octometer.kit.core.store.InMemoryEventLogStore

private const val ONE_CLICK_BODY =
    """{"sessionId":"0b0e4e0e-6a55-4c1e-9a53-0c1f6f7a2d11",""" +
        """"clicks":[{"element":"demo.button-one","ageMs":0}]}"""

/**
 * Tests of the demo `UserIdResolver` (issue #14, step 3). It reads the
 * cookie `demo_user`. Design decision D19 needs the ingest route to drop a
 * click with no user id, so a request with no cookie must give `null`.
 */
class DemoUserIdResolverTest {

    @Test
    fun `a request with the cookie demo_user gives that value as the user id`() = testApplication {
        application {
            routing {
                get("/whoami") {
                    call.respondText(demoUserId(call) ?: "(none)")
                }
            }
        }

        val response = client.get("/whoami") {
            header(HttpHeaders.Cookie, "demo_user=amy")
        }

        assertEquals("amy", response.bodyAsText())
    }

    @Test
    fun `a request with no cookie gives no user id`() = testApplication {
        application {
            routing {
                get("/whoami") {
                    call.respondText(demoUserId(call) ?: "(none)")
                }
            }
        }

        val response = client.get("/whoami")

        assertEquals("(none)", response.bodyAsText())
    }

    @Test
    fun `a click with an empty cookie value gives no user id and no document`() = testApplication {
        val store = InMemoryEventLogStore()
        application {
            demoModule(store)
        }

        val response = client.post("/api/octometer/v1/clicks") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, "demo_user=")
            setBody(ONE_CLICK_BODY)
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertTrue(store.events().isEmpty(), "An empty cookie value must give no user id and no document (security review MAJOR 1).")
    }

    @Test
    fun `a click with a 10 000-character cookie value gives no user id and no document`() = testApplication {
        val store = InMemoryEventLogStore()
        application {
            demoModule(store)
        }

        val response = client.post("/api/octometer/v1/clicks") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, "demo_user=" + "a".repeat(10_000))
            setBody(ONE_CLICK_BODY)
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertTrue(store.events().isEmpty(), "A 10 000-character cookie value must give no user id and no document (security review MAJOR 1).")
    }
}
