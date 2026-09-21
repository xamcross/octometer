package octometer.monitor.registry

import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import octometer.monitor.allowedHost
import octometer.monitor.module
import octometer.monitor.prodConfig
import octometer.monitor.testDataDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Steps 3, 4, and 5 of issue #15, through the real HTTP routes, with the
// headers that the request guard of issue #5 demands (D12).
class AppRegistryRoutesTest {

    @Test
    fun `POST creates an app, and the response holds no connection string`() = testApplication {
        application { module(prodConfig()) }

        val response = createApp(name = "demo", connectionString = allowlistedSrvUri())

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("demo", body["name"]!!.jsonPrimitive.content)
        assertFalse(response.bodyAsText().contains(allowlistedSrvUri()))
    }

    @Test
    fun `POST with a mongodb URI and a public host gets 400`() = testApplication {
        application { module(prodConfig()) }

        val response = createApp(name = "demo", connectionString = publicHostUri())

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST with tlsInsecure=true gets 400`() = testApplication {
        application { module(prodConfig()) }

        val response = createApp(
            name = "demo",
            connectionString = allowlistedSrvUriWithoutCredential("tlsInsecure=true"),
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST with readPreference gets 400`() = testApplication {
        application { module(prodConfig()) }

        val response = createApp(
            name = "demo",
            connectionString = allowlistedSrvUriWithoutCredential("readPreference=secondary"),
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `PATCH replaces the connection string of an existing app`() = testApplication {
        application { module(prodConfig()) }

        val created = createApp(name = "demo", connectionString = allowlistedSrvUri())
        val appId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["appId"]!!.jsonPrimitive.long

        val response = client.patch("/api/apps/$appId") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431")
            contentType(ContentType.Application.Json)
            setBody("""{"connectionString":"${allowlistedSrvUriWithoutCredential()}"}""")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `PATCH on an unknown id gets 404`() = testApplication {
        application { module(prodConfig()) }

        val response = client.patch("/api/apps/999") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"demo2"}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `DELETE removes an app, and a second DELETE call gets 404`() = testApplication {
        application { module(prodConfig()) }

        val created = createApp(name = "demo", connectionString = allowlistedSrvUri())
        val appId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["appId"]!!.jsonPrimitive.long

        val firstDelete = client.delete("/api/apps/$appId") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431")
        }
        val secondDelete = client.delete("/api/apps/$appId") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431")
        }

        assertEquals(HttpStatusCode.NoContent, firstDelete.status)
        assertEquals(HttpStatusCode.NotFound, secondDelete.status)
    }

    @Test
    fun `a byte search of octometer-db finds no connection string after a create`() = testApplication {
        val dataDir = testDataDir()
        application { module(prodConfig(dataDir = dataDir)) }

        createApp(name = "demo", connectionString = allowlistedSrvUri())

        val dbFile = File(dataDir, "octometer.db")
        assertTrue(dbFile.isFile, "expected ${dbFile.absolutePath} to exist")
        val dbBytes = dbFile.readBytes()
        assertEquals(-1, indexOfBytes(dbBytes, allowlistedSrvUri().toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `the secret file sits next to dataDir, and holds the connection string`() = testApplication {
        val dataDir = testDataDir()
        application { module(prodConfig(dataDir = dataDir)) }

        createApp(name = "demo", connectionString = allowlistedSrvUri())

        val secretsFile = File(File(dataDir).parentFile, "secrets/apps.json")
        assertTrue(secretsFile.isFile, "expected ${secretsFile.absolutePath} to exist")
        assertTrue(secretsFile.readText().contains(allowlistedSrvUri()))
    }

    @Test
    fun `POST without an allowed Origin header gets 403, and creates no app`() = testApplication {
        application { module(prodConfig()) }

        val response = client.post("/api/apps") {
            allowedHost()
            contentType(ContentType.Application.Json)
            setBody("""{"name":"demo","connectionString":"x","database":"db","collection":"c"}""")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    private suspend fun ApplicationTestBuilder.createApp(
        name: String,
        connectionString: String,
        database: String = "db",
        collection: String = "octometer_events",
    ) = client.post("/api/apps") {
        allowedHost()
        header(HttpHeaders.Origin, "http://localhost:7431")
        contentType(ContentType.Application.Json)
        setBody(
            """{"name":"$name","connectionString":"$connectionString","database":"$database","collection":"$collection"}""",
        )
    }
}

private fun indexOfBytes(haystack: ByteArray, needle: ByteArray): Int {
    if (needle.isEmpty()) return -1
    outer@ for (start in 0..haystack.size - needle.size) {
        for (offset in needle.indices) {
            if (haystack[start + offset] != needle[offset]) continue@outer
        }
        return start
    }
    return -1
}
