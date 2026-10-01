package octometer.monitor.registry

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import octometer.monitor.allowedHost
import octometer.monitor.captureErrorLogEvents
import octometer.monitor.collect.AppKeyCache
import octometer.monitor.devConfig
import octometer.monitor.module
import octometer.monitor.prodConfig
import octometer.monitor.sessionCookieValue
import octometer.monitor.store.SqliteDatabase
import octometer.monitor.testDataDir
import octometer.monitor.withSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Steps 3, 4, and 5 of issue #15, through the real HTTP routes, with the
// headers that the request guard of issue #5 demands (D12).
//
// Issue #301, D52: every route below now needs a session too. Each test
// reads the config's own dataDir to seed one, through sessionCookieValue,
// and sends it with every request that must reach the route handler.
class AppRegistryRoutesTest {

    @Test
    fun `POST creates an app, and the response holds no connection string`() = testApplication {
        val config = prodConfig()
        application { module(config) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)

        val response = createApp(session, name = "demo", connectionString = allowlistedSrvUri())

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("demo", body["name"]!!.jsonPrimitive.content)
        assertFalse(response.bodyAsText().contains(allowlistedSrvUri()))
    }

    @Test
    fun `POST 201 carries a Location header for the new app`() = testApplication {
        // MINOR 7 of the Ktor review.
        val config = prodConfig()
        application { module(config) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)

        val created = createApp(session, name = "demo", connectionString = allowlistedSrvUri())
        val appId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["appId"]!!.jsonPrimitive.long

        assertEquals("/api/apps/$appId", created.headers[HttpHeaders.Location])
    }

    @Test
    fun `POST with a body that is missing a field gets 400`() = testApplication {
        // MINOR 3 of the Ktor review.
        val config = prodConfig()
        application { module(config) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)

        val response = client.post("/api/apps") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431")
            contentType(ContentType.Application.Json)
            withSession(session)
            setBody("""{"name":"demo","database":"db","collection":"octometer_events"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // Issue #244: an absent collection field takes the default
    // octometer_events. A service test calls the Kotlin constructor and
    // never runs the JSON decode, so this test runs the real route.
    @Test
    fun `POST with no collection field gets 201, and stores octometer_events`() = testApplication {
        val dataDir = testDataDir()
        application { module(prodConfig(dataDir = dataDir)) }
        startApplication()
        val session = sessionCookieValue(dataDir)

        val response = client.post("/api/apps") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431")
            contentType(ContentType.Application.Json)
            withSession(session)
            setBody("""{"name":"demo","connectionString":"${allowlistedSrvUri()}","database":"db"}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("octometer_events", body["collection"]!!.jsonPrimitive.content)
        assertEquals("octometer_events", readCollectionName(dataDir))
    }

    @Test
    fun `POST with a body that is not JSON gets 400`() = testApplication {
        val config = prodConfig()
        application { module(config) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)

        val response = client.post("/api/apps") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431")
            contentType(ContentType.Application.Json)
            withSession(session)
            setBody("this is not json")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // MAJOR 1 (third Ktor review): a request with no body used to raise
    // CannotTransformContentToTypeException. The catch-all of StatusPages
    // then answered 500 for a plain client mistake.
    @Test
    fun `POST with no body gets 400, not 500, and writes no ERROR log line`() = testApplication {
        val config = prodConfig()
        application { module(config) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)

        val (response, errorEvents) = captureErrorLogEvents {
            client.post("/api/apps") {
                allowedHost()
                header(HttpHeaders.Origin, "http://localhost:7431")
                withSession(session)
            }
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(errorEvents.isEmpty(), "a request with no body must write no ERROR log line")
    }

    @Test
    fun `PATCH with no body gets 400, not 500, and writes no ERROR log line`() = testApplication {
        val config = prodConfig()
        application { module(config) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)
        val created = createApp(session, name = "demo", connectionString = allowlistedSrvUri())
        val appId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["appId"]!!.jsonPrimitive.long

        val (response, errorEvents) = captureErrorLogEvents {
            client.patch("/api/apps/$appId") {
                allowedHost()
                header(HttpHeaders.Origin, "http://localhost:7431")
                withSession(session)
            }
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(errorEvents.isEmpty(), "a request with no body must write no ERROR log line")
    }

    @Test
    fun `POST with a duplicate name gets 409`() = testApplication {
        val config = prodConfig()
        application { module(config) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)
        createApp(session, name = "demo", connectionString = allowlistedSrvUri())

        val response = createApp(session, name = "demo", connectionString = allowlistedSrvUriWithoutCredential())

        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun `PATCH with a non-numeric id gets 400`() = testApplication {
        // MINOR 3 of the Ktor review.
        val config = prodConfig()
        application { module(config) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)

        val response = client.patch("/api/apps/not-a-number") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431")
            contentType(ContentType.Application.Json)
            withSession(session)
            setBody("""{"name":"demo2"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `DELETE with a non-numeric id gets 400`() = testApplication {
        val config = prodConfig()
        application { module(config) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)

        val response = client.delete("/api/apps/not-a-number") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431")
            withSession(session)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `DELETE of an unknown id gets 404`() = testApplication {
        val config = prodConfig()
        application { module(config) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)

        val response = client.delete("/api/apps/999") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431")
            withSession(session)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `POST with a mongodb URI and a public host gets 400`() = testApplication {
        val config = prodConfig()
        application { module(config) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)

        val response = createApp(session, name = "demo", connectionString = publicHostUri())

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST with tlsInsecure=true gets 400`() = testApplication {
        val config = prodConfig()
        application { module(config) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)

        val response = createApp(
            session,
            name = "demo",
            connectionString = allowlistedSrvUriWithoutCredential("tlsInsecure=true"),
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST with readPreference gets 400`() = testApplication {
        val config = prodConfig()
        application { module(config) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)

        val response = createApp(
            session,
            name = "demo",
            connectionString = allowlistedSrvUriWithoutCredential("readPreference=secondary"),
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `PATCH replaces the connection string of an existing app`() = testApplication {
        val config = prodConfig()
        application { module(config) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)

        val created = createApp(session, name = "demo", connectionString = allowlistedSrvUri())
        val appId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["appId"]!!.jsonPrimitive.long

        val response = client.patch("/api/apps/$appId") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431")
            contentType(ContentType.Application.Json)
            withSession(session)
            setBody("""{"connectionString":"${withDatabasePath(allowlistedSrvUriWithoutCredential())}"}""")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `PATCH on an unknown id gets 404`() = testApplication {
        val config = prodConfig()
        application { module(config) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)

        val response = client.patch("/api/apps/999") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431")
            contentType(ContentType.Application.Json)
            withSession(session)
            setBody("""{"name":"demo2"}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `DELETE removes an app, and a second DELETE call gets 404`() = testApplication {
        val config = prodConfig()
        application { module(config) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)

        val created = createApp(session, name = "demo", connectionString = allowlistedSrvUri())
        val appId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["appId"]!!.jsonPrimitive.long

        val firstDelete = client.delete("/api/apps/$appId") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431")
            withSession(session)
        }
        val secondDelete = client.delete("/api/apps/$appId") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431")
            withSession(session)
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
            startApplication()
            val session = sessionCookieValue(dataDir)
            createApp(session, name = "demo", connectionString = allowlistedSrvUri())
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
        startApplication()
        val session = sessionCookieValue(dataDir)

        createApp(session, name = "demo", connectionString = allowlistedSrvUri())

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
            startApplication()
            val session = sessionCookieValue(dataDir)

            status = client.post("/api/apps") {
                allowedHost()
                contentType(ContentType.Application.Json)
                withSession(session)
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

    // MAJOR 6 (second Ktor review) and MINOR (second security review).
    // A broken secret file must give 503 on POST, on PATCH, and on
    // DELETE. It must never overwrite the file that it could not read.

    @Test
    fun `POST gives 503 when the secret file is broken, and the file stays unchanged`() {
        val dataDir = testDataDir()
        val secretsFile = File(File(dataDir).parentFile, "secrets").apply { mkdirs() }.let { File(it, "apps.json") }
        val brokenBytes = "{ this is not valid json".toByteArray(Charsets.UTF_8)
        secretsFile.writeBytes(brokenBytes)

        lateinit var status: HttpStatusCode
        testApplication {
            application { module(prodConfig(dataDir = dataDir)) }
            startApplication()
            val session = sessionCookieValue(dataDir)
            status = createApp(session, name = "demo", connectionString = allowlistedSrvUri()).status
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
            startApplication()
            val session = sessionCookieValue(dataDir)
            val created = createApp(session, name = "demo", connectionString = allowlistedSrvUri())
            val appId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["appId"]!!.jsonPrimitive.long

            File(File(dataDir).parentFile, "secrets/apps.json").writeText("{ this is not valid json")

            status = client.patch("/api/apps/$appId") {
                allowedHost()
                header(HttpHeaders.Origin, "http://localhost:7431")
                contentType(ContentType.Application.Json)
                withSession(session)
                setBody("""{"connectionString":"${withDatabasePath(allowlistedSrvUriWithoutCredential())}"}""")
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
            startApplication()
            val session = sessionCookieValue(dataDir)
            val created = createApp(session, name = "demo", connectionString = allowlistedSrvUri())
            val appId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["appId"]!!.jsonPrimitive.long

            File(File(dataDir).parentFile, "secrets/apps.json").writeText("{ this is not valid json")

            status = client.delete("/api/apps/$appId") {
                allowedHost()
                header(HttpHeaders.Origin, "http://localhost:7431")
                withSession(session)
            }.status
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, status)
        assertEquals(1, countAppRows(dataDir), "a 503 must not remove the app row")
    }

    // Issue #272: the create body no longer carries a "database" field;
    // the registry derives the name from the path of the connection
    // string (D13). This helper adds a path when the caller's fixture
    // connection string has none yet, so each existing test still
    // names one database, the same as before.
    private suspend fun ApplicationTestBuilder.createApp(
        session: String,
        name: String,
        connectionString: String,
        collection: String = "octometer_events",
    ) = client.post("/api/apps") {
        allowedHost()
        header(HttpHeaders.Origin, "http://localhost:7431")
        contentType(ContentType.Application.Json)
        withSession(session)
        setBody(
            """{"name":"$name","connectionString":"${withDatabasePath(connectionString)}",""" +
                """"collection":"$collection"}""",
        )
    }

    // Design decision D48 (issue #277): the app publishes its own route
    // list now; POST and PATCH take no pathPatterns field, and give the
    // fixed 400 of PATH_PATTERNS_REJECTED_MESSAGE instead.

    @Test
    fun `POST with a pathPatterns field gets 400 with the fixed reason`() = testApplication {
        val config = prodConfig()
        application { module(config) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)

        val response = client.post("/api/apps") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431")
            contentType(ContentType.Application.Json)
            withSession(session)
            setBody(
                """{"name":"demo","connectionString":"${withDatabasePath(allowlistedSrvUri())}",""" +
                    """"pathPatterns":"/checkout"}""",
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("The app publishes its route list; the monitor takes no list.", body["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `PATCH with a pathPatterns field gets 400 with the fixed reason`() = testApplication {
        val config = prodConfig()
        application { module(config) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)
        val created = createApp(session, name = "demo", connectionString = allowlistedSrvUri())
        val appId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["appId"]!!.jsonPrimitive.long

        val response = client.patch("/api/apps/$appId") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431")
            contentType(ContentType.Application.Json)
            withSession(session)
            setBody("""{"pathPatterns":"/checkout"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("The app publishes its route list; the monitor takes no list.", body["error"]!!.jsonPrimitive.content)
    }

    // Issue #299, D5, D40: GET gives the typed route pattern list back,
    // the list that PUT /api/apps/{id}/routes just stored.

    @Test
    fun `GET apps id gives the id, the name, and the route pattern list, never a connection string`() =
        testApplication {
            val dataDir = testDataDir()
            application { module(prodConfig(dataDir = dataDir)) }
            startApplication()
            val session = sessionCookieValue(dataDir)

            val created = createApp(session, name = "demo", connectionString = allowlistedSrvUri())
            val appId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["appId"]!!.jsonPrimitive.long
            putRoutes(session, appId, listOf("/checkout"))

            val response = client.get("/api/apps/$appId") { allowedHost(); withSession(session) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(appId, body["appId"]!!.jsonPrimitive.long)
            assertEquals("demo", body["name"]!!.jsonPrimitive.content)
            assertEquals(listOf("/checkout"), body["routePatterns"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertFalse(response.bodyAsText().contains(allowlistedSrvUri()))
        }

    // Issue #299, D5, D40, M8: PUT /api/apps/{id}/routes.

    @Test
    fun `PUT apps id routes replaces the list, and the answer holds it`() = testApplication {
        val config = prodConfig()
        application { module(config) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)
        val created = createApp(session, name = "demo", connectionString = allowlistedSrvUri())
        val appId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["appId"]!!.jsonPrimitive.long

        val response = putRoutes(session, appId, listOf("/orders/:id", "/checkout"))

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(
            listOf("/orders/:id", "/checkout"),
            body["routePatterns"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `PUT apps id routes with an empty list clears it, so the default rule applies`() = testApplication {
        val config = prodConfig()
        application { module(config) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)
        val created = createApp(session, name = "demo", connectionString = allowlistedSrvUri())
        val appId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["appId"]!!.jsonPrimitive.long
        putRoutes(session, appId, listOf("/checkout"))

        val response = putRoutes(session, appId, emptyList())

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(emptyList<String>(), body["routePatterns"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `PUT apps id routes with a pattern outside the C42 grammar gets 400 with the fixed reason`() = testApplication {
        val config = prodConfig()
        application { module(config) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)
        val created = createApp(session, name = "demo", connectionString = allowlistedSrvUri())
        val appId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["appId"]!!.jsonPrimitive.long

        val response = putRoutes(session, appId, listOf("no-leading-slash"))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["error"]!!.jsonPrimitive.content.contains("grammar"), "the reason names the grammar rule")
    }

    @Test
    fun `PUT apps id routes of an app of account A gives 404 to account B`() = testApplication {
        val dataDir = testDataDir()
        application { module(devConfig(dataDir = dataDir, clusterUri = "mongodb://127.0.0.1:1/octometer_service")) }
        startApplication()
        val ownerSession = sessionCookieValue(dataDir, "owner-a@example.com")
        val otherSession = sessionCookieValue(dataDir, "owner-b@example.com")
        val created = createAppWithOrigins(ownerSession, name = "demo", origins = listOf("https://demo.example"))
        val appId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["appId"]!!.jsonPrimitive.long

        val response = putRoutes(otherSession, appId, listOf("/checkout"))

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // Issue #299, M8: AppKeyCache (CollectorApps.kt) caches a lookup for
    // 10 seconds. A PUT that writes a fresh list must still let the
    // collector read it inside that window: `clear()` is the method
    // that forces the next lookup to skip the cached, stale entry. This
    // test opens its own SqliteDatabase in its own temporary folder
    // (the rule of the service test), so it needs no running
    // application and no second database on one file.
    @Test
    fun `AppKeyCache clear forces the next lookup to read the fresh route pattern list`() = runBlocking {
        val root = Files.createTempDirectory("octometer-route-cache-test-").toFile()
        val dataDir = File(root, "data").absolutePath
        val database = SqliteDatabase.open(dataDir)
        try {
            val secretStore = SecretStore(dataDir)
            val registry = AppRegistryService(database, secretStore, clusterUri = "mongodb://127.0.0.1:1/octometer_service")
            val accountId = database.write { writer ->
                writer.prepareStatement("INSERT INTO account (email, created_at) VALUES (?, ?)").use { insert ->
                    insert.setString(1, "route-cache-test@example.com")
                    insert.setLong(2, 1_700_000_000_000L)
                    insert.executeUpdate()
                }
                writer.createStatement().use { statement ->
                    statement.executeQuery("SELECT last_insert_rowid()").use { result ->
                        result.next()
                        result.getLong(1)
                    }
                }
            }
            val created = registry.createAppWithOrigins(
                accountId,
                CreateAppWithOriginsRequest("demo", listOf("https://demo.example")),
            ) as CreateAppKeysResult.Created
            val cache = AppKeyCache(database)
            val beforeLookup = cache.lookup(created.keys.appKey)
            assertEquals(null, beforeLookup?.routePatternMatcher, "the app starts with no list")

            registry.updateRoutes(accountId, created.keys.appId, listOf("/orders/:id"))
            val staleLookup = cache.lookup(created.keys.appKey)
            assertEquals(null, staleLookup?.routePatternMatcher, "a lookup inside the TTL, with no clear(), must stay stale")
            cache.clear()
            val freshLookup = cache.lookup(created.keys.appKey)

            assertTrue(freshLookup?.routePatternMatcher != null, "clear() must force a fresh read of the new list")
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `GET apps id on an unknown id gets 404`() = testApplication {
        val config = prodConfig()
        application { module(config) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)

        val response = client.get("/api/apps/999") { allowedHost(); withSession(session) }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `GET apps id with a non-numeric id gets 400`() = testApplication {
        val config = prodConfig()
        application { module(config) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)

        val response = client.get("/api/apps/not-a-number") { allowedHost(); withSession(session) }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // MAJOR 3 of the review of pull request #323: the account filter
    // of D52 had no test of the three new routes of issue #296. Each
    // case below registers an app as account A, with the hosted path
    // (an origin list, no connection string), then calls the route as
    // account B. The account helper of issue #301 (sessionCookieValue
    // with a second email) gives the second account; it reuses the
    // existing row on a second call with the same email, the same
    // rule as every other test of this class.

    @Test
    fun `GET apps id of an app of account A gives 404 to account B`() = testApplication {
        val dataDir = testDataDir()
        application { module(devConfig(dataDir = dataDir, clusterUri = "mongodb://127.0.0.1:1/octometer_service")) }
        startApplication()
        val ownerSession = sessionCookieValue(dataDir, "owner-a@example.com")
        val otherSession = sessionCookieValue(dataDir, "owner-b@example.com")
        val created = createAppWithOrigins(ownerSession, name = "demo", origins = listOf("https://demo.example"))
        val appId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["appId"]!!.jsonPrimitive.long

        val response = client.get("/api/apps/$appId") { allowedHost(); withSession(otherSession) }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `PUT apps id origins of an app of account A gives 404 to account B`() = testApplication {
        val dataDir = testDataDir()
        application { module(devConfig(dataDir = dataDir, clusterUri = "mongodb://127.0.0.1:1/octometer_service")) }
        startApplication()
        val ownerSession = sessionCookieValue(dataDir, "owner-a@example.com")
        val otherSession = sessionCookieValue(dataDir, "owner-b@example.com")
        val created = createAppWithOrigins(ownerSession, name = "demo", origins = listOf("https://demo.example"))
        val appId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["appId"]!!.jsonPrimitive.long

        val response = client.put("/api/apps/$appId/origins") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431")
            contentType(ContentType.Application.Json)
            withSession(otherSession)
            setBody("""{"origins":["https://second.example"]}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `POST apps id secret of an app of account A gives 404 to account B`() = testApplication {
        val dataDir = testDataDir()
        application { module(devConfig(dataDir = dataDir, clusterUri = "mongodb://127.0.0.1:1/octometer_service")) }
        startApplication()
        val ownerSession = sessionCookieValue(dataDir, "owner-a@example.com")
        val otherSession = sessionCookieValue(dataDir, "owner-b@example.com")
        val created = createAppWithOrigins(ownerSession, name = "demo", origins = listOf("https://demo.example"))
        val appId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["appId"]!!.jsonPrimitive.long

        val response = client.post("/api/apps/$appId/secret") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431")
            withSession(otherSession)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `POST with a connectionString field and an origins field gets 400 with the dispatch text`() = testApplication {
        val config = prodConfig()
        application { module(config) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)

        val response = client.post("/api/apps") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431")
            contentType(ContentType.Application.Json)
            withSession(session)
            setBody(
                """{"name":"demo","connectionString":"${allowlistedSrvUri()}",""" +
                    """"origins":["https://demo.example"]}""",
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Send a connection string or an origin list, not both.", body["error"]!!.jsonPrimitive.content)
    }

    // Issue #296, D51, D52, D57: the hosted path of POST /api/apps, for
    // the four account-filter and dispatch tests above.
    private suspend fun ApplicationTestBuilder.createAppWithOrigins(
        session: String,
        name: String,
        origins: List<String>,
    ) = client.post("/api/apps") {
        allowedHost()
        header(HttpHeaders.Origin, "http://localhost:7431")
        contentType(ContentType.Application.Json)
        withSession(session)
        val originsJson = origins.joinToString(",") { "\"$it\"" }
        setBody("""{"name":"$name","origins":[$originsJson]}""")
    }

    // Issue #299, D5, D40: PUT /api/apps/{id}/routes, for each test above.
    private suspend fun ApplicationTestBuilder.putRoutes(session: String, appId: Long, patterns: List<String>) =
        client.put("/api/apps/$appId/routes") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431")
            contentType(ContentType.Application.Json)
            withSession(session)
            val patternsJson = patterns.joinToString(",") { "\"$it\"" }
            setBody("""{"patterns":[$patternsJson]}""")
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

// A single-row read for a test that creates exactly one app.
private fun readCollectionName(dataDir: String): String? {
    val url = "jdbc:sqlite:" + File(dataDir, "octometer.db").absolutePath
    return DriverManager.getConnection(url).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT collection_name FROM app LIMIT 1").use { result ->
                if (!result.next()) null else result.getString(1)
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
