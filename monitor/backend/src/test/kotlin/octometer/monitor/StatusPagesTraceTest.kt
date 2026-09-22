package octometer.monitor

import ch.qos.logback.classic.spi.ILoggingEvent
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Issue #150. StatusPages writes a TRACE line with the full request URL.
// This happens for a response with no status handler. It also happens
// for a response with no status set at all. ContentNegotiation writes a
// TRACE line with the full request URI too (BLOCKER 1, review of pull
// request #170). The default root level of `logback.xml` is INFO, so
// none of these lines print in a real deployment. A diagnosis can raise
// the root level to TRACE. A log line must still hold no user id and
// no query string, at each level. D13 puts the user id, the session
// id, and `firstPath` in the query string.
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

    // BLOCKER 1, review of pull request #170: a 404 from an unmatched
    // route goes through ContentNegotiation, not through a route handler.
    // The plugin answers with a bare HttpStatusCode, an ignored type.
    // ResponseConverter.kt then names the request URI in a TRACE line.
    @Test
    fun `a 404 from an unmatched route writes no line with the userId marker, at any level`() {
        val marker = "trace-marker-g7h8i9"
        testApplication {
            application { module(prodConfig()) }

            val (response, events) = captureLogEvents {
                client.get("/api/no-such-route?userId=$marker") { allowedHost() }
            }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertNoLineHoldsMarker(events, marker)
        }
    }

    // MINOR 4, review of pull request #170: an empty capture gives a
    // false green. This assert proves that the capture read a real
    // event.
    private fun assertNoLineHoldsMarker(events: List<ILoggingEvent>, marker: String) {
        assertTrue(events.isNotEmpty(), "the capture read no log event")
        for (event in events) {
            assertFalse(event.formattedMessage.contains(marker), "a log line held the marker: ${event.formattedMessage}")
            assertFalse(
                event.formattedMessage.contains("userId="),
                "a log line held the query string: ${event.formattedMessage}",
            )
            // MINOR 3, review of pull request #170: a line with a cause
            // can hold the marker in the exception message, not only in
            // the formatted message (the pattern of
            // UserErasureRoutesTest.kt).
            val exceptionMessage = event.throwableProxy?.message
            if (exceptionMessage != null) {
                assertFalse(exceptionMessage.contains(marker), "an exception message held the marker: $exceptionMessage")
            }
        }
    }
}
