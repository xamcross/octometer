package octometer.demo

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
