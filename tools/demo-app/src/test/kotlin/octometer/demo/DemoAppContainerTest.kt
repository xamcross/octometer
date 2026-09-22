package octometer.demo

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.bson.Document
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import octometer.kit.mongo.store.MongoEventLogStore

/**
 * Tests of the demo app against a real MongoDB server (issue #14). Each
 * test needs Docker; a machine with no Docker skips the whole class.
 */
@Testcontainers(disabledWithoutDocker = true)
class DemoAppContainerTest {

    companion object {
        @Container
        @JvmStatic
        private val MONGO = MongoDBContainer(DockerImageName.parse("mongo:8.0"))
    }

    // Named mongoClient, not client: testApplication gives its own field
    // named client, the Ktor test HTTP client.
    private lateinit var mongoClient: MongoClient
    private lateinit var databaseName: String

    @BeforeTest
    fun openClient() {
        mongoClient = MongoClients.create(MONGO.connectionString)
        databaseName = "exampledb_test_" + System.nanoTime()
    }

    @AfterTest
    fun closeClient() {
        mongoClient.close()
    }

    private fun eventCollection() = mongoClient.getDatabase(databaseName).getCollection("octometer_events")

    @Test
    fun `one click with the cookie demo_user gives one document`() = testApplication {
        val store = MongoEventLogStore(mongoClient.getDatabase(databaseName))
        application {
            demoModule(store)
        }

        val response = client.post("/api/octometer/v1/clicks") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, "demo_user=amy")
            setBody(
                """{"sessionId":"0b0e4e0e-6a55-4c1e-9a53-0c1f6f7a2d11",""" +
                    """"clicks":[{"element":"demo.button-one","ageMs":0,"path":"/"}]}""",
            )
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals(1L, eventCollection().countDocuments())
        val stored = eventCollection().find().first() as Document
        assertEquals("amy", stored.getString("userId"))
        assertEquals("demo.button-one", stored.getString("element"))
        assertEquals("/", stored.getString("path"), "A click on the demo page must store the path / (Ktor review MAJOR 2).")
    }

    @Test
    fun `a click with an unknown path stores the fallback path other`() = testApplication {
        val store = MongoEventLogStore(mongoClient.getDatabase(databaseName))
        application {
            demoModule(store)
        }

        val response = client.post("/api/octometer/v1/clicks") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, "demo_user=amy")
            setBody(
                """{"sessionId":"0b0e4e0e-6a55-4c1e-9a53-0c1f6f7a2d11",""" +
                    """"clicks":[{"element":"demo.button-one","ageMs":0,"path":"/deep/page"}]}""",
            )
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals(1L, eventCollection().countDocuments())
        val stored = eventCollection().find().first() as Document
        assertEquals("/other", stored.getString("path"), "A path with no match must store /other (contract rule C42).")
    }

    @Test
    fun `one click with no cookie gives no document`() = testApplication {
        val store = MongoEventLogStore(mongoClient.getDatabase(databaseName))
        application {
            demoModule(store)
        }

        val response = client.post("/api/octometer/v1/clicks") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"sessionId":"0b0e4e0e-6a55-4c1e-9a53-0c1f6f7a2d11",""" +
                    """"clicks":[{"element":"demo.button-one","ageMs":0}]}""",
            )
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals(0L, eventCollection().countDocuments(), "A click with no user id must give no document (D19).")
    }

    @Test
    fun `--generate writes a minimum of 100 events for 5 user ids through the store`() {
        val store = MongoEventLogStore(mongoClient.getDatabase(databaseName))

        val written = SyntheticClickGenerator.generate(store)

        assertEquals(written.toLong(), eventCollection().countDocuments())
        val storedUserIds = HashSet<String>()
        for (document in eventCollection().find()) {
            storedUserIds.add(document.getString("userId"))
        }
        assertEquals(5, storedUserIds.size)
        assertEquals(SyntheticClickGenerator.DEMO_USER_IDS.toSet(), storedUserIds)
        assert(eventCollection().countDocuments() >= 100) { "The generator must write a minimum of 100 events." }
    }
}
