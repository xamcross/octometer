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
import java.sql.DriverManager
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
    fun `POST 201 carries a Location header for the new app`() = testApplication {
        // MINOR 7 of the Ktor review.
        application { module(prodConfig()) }

        val created = createApp(name = "demo", connectionString = allowlistedSrvUri())
        val appId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["appId"]!!.jsonPrimitive.long

        assertEquals("/api/apps/$appId", created.headers[HttpHeaders.Location])
    }

    @Test
    fun `POST with a body that is missing a field gets 400`() = testApplication {
        // MINOR 3 of the Ktor review.
        application { module(prodConfig()) }

        val response = client.post("/api/apps") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"demo","database":"db","collection":"octometer_events"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST with a body that is not JSON gets 400`() = testApplication {
        application { module(prodConfig()) }

        val response = client.post("/api/apps") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431")
            contentType(ContentType.Application.Json)
            setBody("this is not json")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST with a duplicate name gets 409`() = testApplication {
        application { module(prodConfig()) }
        createApp(name = "demo", connectionString = allowlistedSrvUri())

        val response = createApp(name = "demo", connectionString = allowlistedSrvUriWithoutCredential())

        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun `PATCH with a non-numeric id gets 400`() = testApplication {
        // MINOR 3 of the Ktor review.
        application { module(prodConfig()) }

        val response = client.patch("/api/apps/not-a-number") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"demo2"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `DELETE with a non-numeric id gets 400`() = testApplication {
        application { module(prodConfig()) }

        val response = client.delete("/api/apps/not-a-number") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `DELETE of an unknown id gets 404`() = testApplication {
        application { module(prodConfig()) }

        val response = client.delete("/api/apps/999") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
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
    fun `a byte search of the store files finds no connection string after a create`() {
        // BLOCKER 1 of the Ktor review: the store runs in WAL mode (D3), so
        // a fresh row lives in octometer.db-wal, not in octometer.db, while
        // the store is open. The search must run after the store closes,
        // and it must search every file of the store, not one file.
        val dataDir = testDataDir()
        testApplication {
            application { module(prodConfig(dataDir = dataDir)) }
            createApp(name = "demo", connectionString = allowlistedSrvUri())
        }

        val needle = allowlistedSrvUri().toByteArray(Charsets.UTF_8)
        val storeFiles = File(dataDir).listFiles { file -> file.name.startsWith("octometer.db") }
        assertTrue(!storeFiles.isNullOrEmpty(), "expected the store files in $dataDir")
        for (file in storeFiles) {
            assertEquals(-1, indexOfBytes(file.readBytes(), needle), file.name)
        }
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
    fun `POST without an allowed Origin header gets 403, and creates no app`() {
        // MINOR 2 of the Ktor review, and MINOR 7 of the security review:
        // the old test asserted the status code only.
        val dataDir = testDataDir()
        lateinit var status: HttpStatusCode
        testApplication {
            application { module(prodConfig(dataDir = dataDir)) }

            status = client.post("/api/apps") {
                allowedHost()
                contentType(ContentType.Application.Json)
                setBody("""{"name":"demo","connectionString":"x","database":"db","collection":"c"}""")
            }.status
        }

        assertEquals(HttpStatusCode.Forbidden, status)
        assertFalse(
            File(File(dataDir).parentFile, "secrets/apps.json").exists(),
            "the rejected request must write no secret",
        )
        assertEquals(0, countAppRows(dataDir))
    }

    // MAJOR 6 (second Ktor review) and MINOR (second security review): a
    // broken secret file must give 503 on POST, on PATCH, and on DELETE,
    // and it must never overwrite the file that it could not read.

    @Test
    fun `POST gives 503 when the secret file is broken, and the file stays unchanged`() {
        val dataDir = testDataDir()
        val secretsFile = File(File(dataDir).parentFile, "secrets").apply { mkdirs() }.let { File(it, "apps.json") }
        val brokenBytes = "{ this is not valid json".toByteArray(Charsets.UTF_8)
        secretsFile.writeBytes(brokenBytes)

        lateinit var status: HttpStatusCode
        testApplication {
            application { module(prodConfig(dataDir = dataDir)) }
            status = createApp(name = "demo", connectionString = allowlistedSrvUri()).status
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, status)
        assertTrue(brokenBytes.contentEquals(secretsFile.readBytes()), "the broken file must stay exactly as it was")
        assertEquals(0, countAppRows(dataDir))
    }

    @Test
    fun `PATCH gives 503 when the secret file is broken, and leaves the app row unchanged`() {
        val dataDir = testDataDir()
        lateinit var status: HttpStatusCode
        testApplication {
            application { module(prodConfig(dataDir = dataDir)) }
            val created = createApp(name = "demo", connectionString = allowlistedSrvUri())
            val appId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["appId"]!!.jsonPrimitive.long

            File(File(dataDir).parentFile, "secrets/apps.json").writeText("{ this is not valid json")

            status = client.patch("/api/apps/$appId") {
                allowedHost()
                header(HttpHeaders.Origin, "http://localhost:7431")
                contentType(ContentType.Application.Json)
                setBody("""{"connectionString":"${allowlistedSrvUriWithoutCredential()}"}""")
            }.status
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, status)
        assertEquals(1, countAppRows(dataDir), "a 503 must not remove the app row")
    }

    @Test
    fun `DELETE gives 503 when the secret file is broken, instead of a raw 500`() {
        val dataDir = testDataDir()
        lateinit var status: HttpStatusCode
        testApplication {
            application { module(prodConfig(dataDir = dataDir)) }
            val created = createApp(name = "demo", connectionString = allowlistedSrvUri())
            val appId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["appId"]!!.jsonPrimitive.long

            File(File(dataDir).parentFile, "secrets/apps.json").writeText("{ this is not valid json")

            status = client.delete("/api/apps/$appId") {
                allowedHost()
                header(HttpHeaders.Origin, "http://localhost:7431")
            }.status
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, status)
        assertEquals(1, countAppRows(dataDir), "a 503 must not remove the app row")
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

// The store closed when testApplication ended, so a fresh, short-lived
// JDBC connection can read the file directly here, with no pragma of its
// own: a plain count needs none.
private fun countAppRows(dataDir: String): Int {
    val url = "jdbc:sqlite:" + File(dataDir, "octometer.db").absolutePath
    return DriverManager.getConnection(url).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM app").use { result ->
                result.next()
                result.getInt(1)
            }
        }
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
