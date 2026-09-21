package octometer.kit.ktor

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import octometer.kit.core.ingest.IngestEvent
import octometer.kit.core.ingest.IngestSettings
import octometer.kit.core.store.EventLogStore
import octometer.kit.core.store.InMemoryEventLogStore

/**
 * Tests of the ingest route against design section 4.2 and contract rules
 * C12 to C19, C32, C33, C36, and C37.
 */
class IngestRouteTest {

    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-09-21T10:00:00Z"), ZoneOffset.UTC)

    private val validBody = """{"sessionId":"0b0e4e0e-6a55-4c1e-9a53-0c1f6f7a2d11",""" +
        """"clicks":[{"element":"checkout.save","ageMs":1200}]}"""

    @Test
    fun `a valid batch gives 204 and stores the events`() = testApplication {
        val store = InMemoryEventLogStore()
        application {
            routing {
                octometerIngestRoute(store = store, settings = IngestSettings(true)) { "user-1" }
            }
        }

        val response = client.post(DEFAULT_INGEST_PATH) {
            contentType(ContentType.Application.Json)
            setBody(validBody)
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        val stored = store.events()
        assertEquals(1, stored.size)
        assertEquals("checkout.save", stored[0].element())
        assertEquals("user-1", stored[0].userId())
    }

    @Test
    fun `application json with a charset parameter passes`() = testApplication {
        val store = InMemoryEventLogStore()
        application {
            routing {
                octometerIngestRoute(store = store, settings = IngestSettings(true)) { "user-1" }
            }
        }

        val response = client.post(DEFAULT_INGEST_PATH) {
            header(HttpHeaders.ContentType, "application/json; charset=utf-8")
            setBody(validBody)
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `text plain gives 415`() = testApplication {
        val store = InMemoryEventLogStore()
        application {
            routing {
                octometerIngestRoute(store = store) { null }
            }
        }

        val response = client.post(DEFAULT_INGEST_PATH) {
            contentType(ContentType.Text.Plain)
            setBody(validBody)
        }

        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
        assertTrue(store.events().isEmpty())
    }

    @Test
    fun `an absent content type gives 415`() = testApplication {
        val store = InMemoryEventLogStore()
        application {
            routing {
                octometerIngestRoute(store = store) { null }
            }
        }

        val response = client.post(DEFAULT_INGEST_PATH) {
            setBody(validBody)
        }

        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    }

    @Test
    fun `an invalid body gives 400`() = testApplication {
        val store = InMemoryEventLogStore()
        application {
            routing {
                octometerIngestRoute(store = store) { "user-1" }
            }
        }

        val response = client.post(DEFAULT_INGEST_PATH) {
            contentType(ContentType.Application.Json)
            setBody("""{"sessionId":"not-a-uuid","clicks":[]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(store.events().isEmpty())
    }

    @Test
    fun `a body above 16 KB gives 400 and the route reads no unlimited body`() = testApplication {
        val store = InMemoryEventLogStore()
        application {
            routing {
                octometerIngestRoute(store = store) { "user-1" }
            }
        }

        val oversizedElement = "a".repeat(10 * 1024 * 1024)
        val oversizedBody = """{"sessionId":"0b0e4e0e-6a55-4c1e-9a53-0c1f6f7a2d11",""" +
            """"clicks":[{"element":"$oversizedElement","ageMs":1200}]}"""

        val response = client.post(DEFAULT_INGEST_PATH) {
            contentType(ContentType.Application.Json)
            setBody(oversizedBody)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(store.events().isEmpty())
    }

    @Test
    fun `no response holds a CORS header`() = testApplication {
        val store = InMemoryEventLogStore()
        application {
            routing {
                octometerIngestRoute(store = store, settings = IngestSettings(true)) { "user-1" }
            }
        }

        val response = client.post(DEFAULT_INGEST_PATH) {
            contentType(ContentType.Application.Json)
            setBody(validBody)
        }

        assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun `the store call runs on Dispatchers IO`() = testApplication {
        val recordedThreadName = AtomicReference<String>()
        val store = object : EventLogStore {
            override fun append(events: List<IngestEvent>, userId: String?) {
                recordedThreadName.set(Thread.currentThread().name)
            }

            override fun deleteByUserId(userId: String) {
                // Not used by this test.
            }
        }
        application {
            routing {
                octometerIngestRoute(store = store, settings = IngestSettings(true), clock = fixedClock) { "user-1" }
            }
        }

        val response = client.post(DEFAULT_INGEST_PATH) {
            contentType(ContentType.Application.Json)
            setBody(validBody)
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        val threadName = recordedThreadName.get()
        assertTrue(
            threadName != null && threadName.contains("DefaultDispatcher-worker"),
            "Expected the store call on a Dispatchers.IO worker thread, but it ran on \"$threadName\".",
        )
    }

    @Test
    fun `a store defect gives 500 and never 400`() = testApplication {
        val store = object : EventLogStore {
            override fun append(events: List<IngestEvent>, userId: String?) {
                throw IllegalStateException("The test store simulates a write failure.")
            }

            override fun deleteByUserId(userId: String) {
                // Not used by this test.
            }
        }
        application {
            routing {
                octometerIngestRoute(store = store, settings = IngestSettings(true)) { "user-1" }
            }
        }

        val response = client.post(DEFAULT_INGEST_PATH) {
            contentType(ContentType.Application.Json)
            setBody(validBody)
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
    }

    @Test
    fun `a UserIdResolver defect gives 500 and never 400`() = testApplication {
        val store = InMemoryEventLogStore()
        application {
            routing {
                octometerIngestRoute(store = store, settings = IngestSettings(true)) { "" }
            }
        }

        val response = client.post(DEFAULT_INGEST_PATH) {
            contentType(ContentType.Application.Json)
            setBody(validBody)
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertTrue(store.events().isEmpty())
    }

    @Test
    fun `a dropped anonymous batch still gives 204`() = testApplication {
        val store = InMemoryEventLogStore()
        application {
            routing {
                octometerIngestRoute(store = store, settings = IngestSettings(false)) { null }
            }
        }

        val response = client.post(DEFAULT_INGEST_PATH) {
            contentType(ContentType.Application.Json)
            setBody(validBody)
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertTrue(store.events().isEmpty())
    }
}
