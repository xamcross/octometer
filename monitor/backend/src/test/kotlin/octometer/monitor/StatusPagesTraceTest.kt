package octometer.monitor

import ch.qos.logback.classic.spi.ILoggingEvent
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

// Issue #150. The plugin StatusPages writes a TRACE line with the full
// request URL for a response with no registered status handler, and for
// a response with no status set at all. The default root level of
// `logback.xml` is INFO, so that line never prints in a real
// deployment. A person who raises the root level to TRACE for a
// diagnosis must still see no user id and no query string in a log
// line, at each level (D13 puts a user id in the query string).
class StatusPagesTraceTest {

    @Test
    fun `a 404 from an unregistered app writes no line with the userId marker, at any level`() {
        val marker = "trace-marker-a1b2c3"
        testApplication {
            application { module(prodConfig()) }

            val (response, events) = captureLogEvents {
                client.get("/api/apps/777777/users?userId=$marker") { allowedHost() }
            }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertNoLineHoldsMarker(events, marker)
        }
    }

    @Test
    fun `a 400 from a wrong page parameter writes no line with the userId marker, at any level`() {
        val marker = "trace-marker-d4e5f6"
        testApplication {
            application { module(prodConfig()) }

            val (response, events) = captureLogEvents {
                client.get("/api/apps/1/users?userId=$marker&page=not-a-number") { allowedHost() }
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertNoLineHoldsMarker(events, marker)
        }
    }

    private fun assertNoLineHoldsMarker(events: List<ILoggingEvent>, marker: String) {
        for (event in events) {
            assertFalse(event.formattedMessage.contains(marker), "a log line held the marker: ${event.formattedMessage}")
            assertFalse(
                event.formattedMessage.contains("userId="),
                "a log line held the query string: ${event.formattedMessage}",
            )
        }
    }
}
