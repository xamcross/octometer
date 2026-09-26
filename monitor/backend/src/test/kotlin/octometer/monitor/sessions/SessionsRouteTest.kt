package octometer.monitor.sessions

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLQueryComponent
import io.ktor.server.testing.testApplication
import java.sql.ResultSet
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import octometer.monitor.allowedHost
import octometer.monitor.captureLogEvents
import octometer.monitor.devConfig
import octometer.monitor.module
import octometer.monitor.store.SqliteDatabase
import octometer.monitor.testDataDir
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Issue #113: GET /api/apps/{appId}/sessions, the anonymous session
// table. Source: docs/superpowers/specs/2026-09-21-octometer-design.md,
// D13, D44, section 6, and the maintainer's correction of 2026-09-26.
class SessionsRouteTest {

    private val dataDir = testDataDir()
    private lateinit var database: SqliteDatabase

    @BeforeTest
    fun setUp() = runBlocking {
        database = SqliteDatabase.open(dataDir)
    }

    @AfterTest
    fun tearDown() {
        database.close()
    }

    // -- The selection and the row shape (steps 2, 3, 4) --------------------

    @Test
    fun `a session with a start row and clicks gives the path, the source, the start time, and the click count`() =
        runBlocking {
            val appId = insertApp(database, "shop")
            insertEvent(
                database, appId, "e1", "s1", userId = null, element = "octo:session-start", kind = 1,
                path = "/landing", referrerHost = "example.com", ts = 1_700_000_000_000L,
            )
            insertEvent(database, appId, "e2", "s1", userId = null, element = "checkout.save", kind = 0, ts = 1_700_000_001_000L)
            insertEvent(database, appId, "e3", "s1", userId = null, element = "checkout.save", kind = 0, ts = 1_700_000_002_000L)

            val row = successRows(database, appId).single()

            assertEquals("s1", row.sessionId)
            assertEquals("/landing", row.firstPath)
            assertEquals("example.com", row.source)
            assertEquals("2023-11-14T22:13:20.000Z", row.startTime)
            assertEquals(2L, row.clicks)
            assertNull(row.userId)
        }

    @Test
    fun `a session with clicks only gives the path (unknown) and the time of the first click`() = runBlocking {
        val appId = insertApp(database, "shop")
        insertEvent(database, appId, "e1", "s1", userId = null, element = "checkout.save", kind = 0, ts = 1_700_000_005_000L)
        insertEvent(database, appId, "e2", "s1", userId = null, element = "checkout.save", kind = 0, ts = 1_700_000_003_000L)

        val row = successRows(database, appId).single()

        assertEquals("(unknown)", row.firstPath)
        assertEquals("(direct)", row.source)
        assertEquals("2023-11-14T22:13:23.000Z", row.startTime, "the earlier of the two clicks")
        assertEquals(2L, row.clicks)
    }

    @Test
    fun `a start row without referrerHost gives the source (direct)`() = runBlocking {
        val appId = insertApp(database, "shop")
        insertEvent(
            database, appId, "e1", "s1", userId = null, element = "octo:session-start", kind = 1,
            path = "/landing", referrerHost = null,
        )

        val row = successRows(database, appId).single()

        assertEquals("(direct)", row.source)
    }

    @Test
    fun `a session with a later sign-in shows the user id of that sign-in`() = runBlocking {
        val appId = insertApp(database, "shop")
        insertEvent(
            database, appId, "e1", "s1", userId = null, element = "octo:session-start", kind = 1,
            path = "/landing", ts = 1_700_000_000_000L,
        )
        insertEvent(database, appId, "e2", "s1", userId = null, element = "checkout.save", kind = 0, ts = 1_700_000_001_000L)
        insertEvent(database, appId, "e3", "s1", userId = "user-1", element = "checkout.save", kind = 0, ts = 1_700_000_002_000L)

        val row = successRows(database, appId).single()

        assertEquals("user-1", row.userId)
        assertEquals(2L, row.clicks, "the click after the sign-in still counts")
    }

    // Section 6: one session can hold two users. The rule picks the
    // earliest sign-in row by ts, never the last.
    @Test
    fun `a session with two later sign-ins shows the earliest one`() = runBlocking {
        val appId = insertApp(database, "shop")
        insertEvent(database, appId, "e1", "s1", userId = null, element = "octo:session-start", kind = 1, ts = 1_700_000_000_000L)
        insertEvent(database, appId, "e2", "s1", userId = "user-second", element = "checkout.save", kind = 0, ts = 1_700_000_003_000L)
        insertEvent(database, appId, "e3", "s1", userId = "user-first", element = "checkout.save", kind = 0, ts = 1_700_000_002_000L)

        val row = successRows(database, appId).single()

        assertEquals("user-first", row.userId)
    }

    // The selection rule (the maintainer's correction of 2026-09-26): a
    // session with no event of user_id IS NULL never qualifies, even
    // when it holds clicks.
    @Test
    fun `a session with every event signed in from the first click never appears`() = runBlocking {
        val appId = insertApp(database, "shop")
        insertEvent(database, appId, "e1", "s1", userId = "user-1", element = "checkout.save", kind = 0)

        val rows = successRows(database, appId)

        assertEquals(emptyList(), rows)
    }

    @Test
    fun `a second app never mixes its rows into the first app's sessions`() = runBlocking {
        val appOne = insertApp(database, "app-one")
        val appTwo = insertApp(database, "app-two")
        insertEvent(database, appOne, "e1", "s1", userId = null, element = "checkout.save", kind = 0)
        insertEvent(database, appTwo, "e2", "s2", userId = null, element = "checkout.save", kind = 0)

        val rowsOfAppOne = successRows(database, appOne)
        val rowsOfAppTwo = successRows(database, appTwo)

        assertEquals("s1", rowsOfAppOne.single().sessionId)
        assertEquals("s2", rowsOfAppTwo.single().sessionId)
    }

    @Test
    fun `the order is startTime DESC then sessionId ASC`() = runBlocking {
        val appId = insertApp(database, "shop")
        insertEvent(database, appId, "e1", "s-older-a", userId = null, element = "octo:session-start", kind = 1, ts = 1_000L)
        insertEvent(database, appId, "e2", "s-older-b", userId = null, element = "octo:session-start", kind = 1, ts = 1_000L)
        insertEvent(database, appId, "e3", "s-newest", userId = null, element = "octo:session-start", kind = 1, ts = 2_000L)

        val rows = successRows(database, appId)

        assertEquals(listOf("s-newest", "s-older-a", "s-older-b"), rows.map { it.sessionId })
    }

    @Test
    fun `firstPath filters the rows, and the value is a bound parameter`() = runBlocking {
        val appId = insertApp(database, "shop")
        insertEvent(database, appId, "e1", "s1", userId = null, element = "octo:session-start", kind = 1, path = "/a")
        insertEvent(database, appId, "e2", "s2", userId = null, element = "octo:session-start", kind = 1, path = "/b")

        val result = loadAnonymousSessions(database, appId, firstPath = "/a", requestedPage = 1)
        check(result is SessionsResult.Success)

        assertEquals(listOf("s1"), result.rows.map { it.sessionId })
    }

    @Test
    fun `firstPath never matches a session without a start row`() = runBlocking {
        val appId = insertApp(database, "shop")
        insertEvent(database, appId, "e1", "s1", userId = null, element = "checkout.save", kind = 0)

        val result = loadAnonymousSessions(database, appId, firstPath = "/a", requestedPage = 1)
        check(result is SessionsResult.Success)

        assertEquals(emptyList(), result.rows)
    }

    @Test
    fun `an unknown app id gives AppNotFound`() = runBlocking {
        val result = loadAnonymousSessions(database, appId = 999_999L, firstPath = null, requestedPage = 1)

        assertEquals(SessionsResult.AppNotFound, result)
    }

    // -- The page rule of issue #112 (decision 1) ---------------------------

    @Test
    fun `one page holds a maximum of 50 rows, and page 2 gives the rest`() = runBlocking {
        val appId = insertApp(database, "shop")
        repeat(55) { index ->
            insertEvent(
                database, appId, "e$index", "s%03d".format(index), userId = null,
                element = "octo:session-start", kind = 1, ts = 1_000L + index,
            )
        }

        val pageOne = loadAnonymousSessions(database, appId, firstPath = null, requestedPage = 1)
        val pageTwo = loadAnonymousSessions(database, appId, firstPath = null, requestedPage = 2)
        check(pageOne is SessionsResult.Success)
        check(pageTwo is SessionsResult.Success)

        assertEquals(50, pageOne.rows.size)
        assertEquals(5, pageTwo.rows.size)
        assertEquals(2, pageOne.pageCount)
    }

    @Test
    fun `a requested page above pageCount clamps to the last page`() = runBlocking {
        val appId = insertApp(database, "shop")
        insertEvent(database, appId, "e1", "s1", userId = null, element = "octo:session-start", kind = 1)

        val result = loadAnonymousSessions(database, appId, firstPath = null, requestedPage = 99)
        check(result is SessionsResult.Success)

        assertEquals(1, result.page)
        assertEquals(1, result.pageCount)
    }

    // SQL MAJOR 3 of the users review: the count statement and the page
    // statement must share one snapshot. A write that commits between
    // them must change neither result.
    @Test
    fun `a committed insert between the count statement and the page statement changes neither result`() =
        runBlocking {
            val appId = insertApp(database, "shop")
            repeat(60) { index ->
                insertEvent(
                    database, appId, "e$index", "s%03d".format(index), userId = null,
                    element = "octo:session-start", kind = 1, ts = 1_000L + index,
                )
            }
            val expectedFirstPage = (0 until 50).map { index -> "s%03d".format(59 - index) }

            val (totalBeforeInsert, pageRows) = database.read { reader ->
                val total = countSessions(reader, appId, firstPath = null)
                runBlocking {
                    insertEvent(
                        database, appId, "e-extra", "s-extra", userId = null,
                        element = "octo:session-start", kind = 1, ts = 999_999L,
                    )
                }
                val rows = readSessionsPage(reader, appId, firstPath = null, page = 1)
                total to rows
            }

            assertEquals(60, totalBeforeInsert, "the count statement must not see the row that commits later")
            assertEquals(
                expectedFirstPage,
                pageRows.map { it.sessionId },
                "the page statement must not see the row that commits between the two statements",
            )
        }

    // -- The bound parameter (SQL review lesson) -----------------------------

    @Test
    fun `a hostile firstPath value is a bound parameter, thus the rows stay unchanged`() = runBlocking {
        val appId = insertApp(database, "shop")
        insertEvent(database, appId, "e1", "s1", userId = null, element = "octo:session-start", kind = 1, path = "/a")
        database.close()

        testApplication {
            application { module(devConfig(dataDir)) }

            val quote = client.get(
                "/api/apps/$appId/sessions?anonymous=true&firstPath=" + "'".encodeURLQueryComponent(),
            ) { allowedHost() }
            val dropTable = client.get(
                "/api/apps/$appId/sessions?anonymous=true&firstPath=" +
                    "'; DROP TABLE event; --".encodeURLQueryComponent(),
            ) { allowedHost() }

            assertTrue(quote.status == HttpStatusCode.OK || quote.status == HttpStatusCode.BadRequest)
            assertTrue(dropTable.status == HttpStatusCode.OK || dropTable.status == HttpStatusCode.BadRequest)
        }
        database = SqliteDatabase.open(dataDir)
        val count = database.read { reader ->
            reader.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM event").use { result ->
                    result.next()
                    result.getInt(1)
                }
            }
        }
        assertEquals(1, count, "the event table keeps its one row")
    }

    // -- The query plan (acceptance criterion) -------------------------------

    @Test
    fun `EXPLAIN QUERY PLAN shows the index event_start for the start-row statement`() = runBlocking {
        val appId = insertApp(database, "shop")
        insertEvent(database, appId, "e1", "s1", userId = null, element = "octo:session-start", kind = 1)

        val plan = database.read { reader ->
            reader.prepareStatement("EXPLAIN QUERY PLAN $SESSION_STARTS_SQL").use { statement ->
                statement.setLong(1, appId)
                statement.executeQuery().use { result -> collectPlanLines(result) }
            }
        }

        assertTrue(plan.any { it.contains("INDEX event_start") }, plan.toString())
    }

    // -- The HTTP layer (steps 1, 5) ------------------------------------------

    @Test
    fun `GET with anonymous=true answers 200 with page, pageCount, and the six row fields`() = runBlocking {
        val appId = insertApp(database, "shop")
        insertEvent(
            database, appId, "e1", "s1", userId = null, element = "octo:session-start", kind = 1,
            path = "/landing", referrerHost = "example.com",
        )
        database.close()

        testApplication {
            application { module(devConfig(dataDir)) }

            val response = client.get("/api/apps/$appId/sessions?anonymous=true") { allowedHost() }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(1, body.getValue("page").jsonPrimitive.content.toInt())
            assertEquals(1, body.getValue("pageCount").jsonPrimitive.content.toInt())
            val row = body.getValue("rows").jsonArray.single().jsonObject
            assertTrue(row.containsKey("sessionId"))
            assertTrue(row.containsKey("firstPath"))
            assertTrue(row.containsKey("source"))
            assertTrue(row.containsKey("startTime"))
            assertTrue(row.containsKey("clicks"))
            assertTrue(row.containsKey("userId"))
        }
        database = SqliteDatabase.open(dataDir)
    }

    @Test
    fun `GET without anonymous gets 400`() = runBlocking {
        val appId = insertApp(database, "shop")
        database.close()

        testApplication {
            application { module(devConfig(dataDir)) }

            val response = client.get("/api/apps/$appId/sessions") { allowedHost() }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
        database = SqliteDatabase.open(dataDir)
    }

    @Test
    fun `GET with anonymous=false gets 400`() = runBlocking {
        val appId = insertApp(database, "shop")
        database.close()

        testApplication {
            application { module(devConfig(dataDir)) }

            val response = client.get("/api/apps/$appId/sessions?anonymous=false") { allowedHost() }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
        database = SqliteDatabase.open(dataDir)
    }

    @Test
    fun `GET with anonymous repeated as true and false gets 400`() = runBlocking {
        val appId = insertApp(database, "shop")
        database.close()

        testApplication {
            application { module(devConfig(dataDir)) }

            val response =
                client.get("/api/apps/$appId/sessions?anonymous=true&anonymous=false") { allowedHost() }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
        database = SqliteDatabase.open(dataDir)
    }

    @Test
    fun `GET with a non-numeric app id gets 400`() = runBlocking {
        database.close()

        testApplication {
            application { module(devConfig(dataDir)) }

            val response = client.get("/api/apps/not-a-number/sessions?anonymous=true") { allowedHost() }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
        database = SqliteDatabase.open(dataDir)
    }

    @Test
    fun `GET with a bad page gets 400, not 500`() = runBlocking {
        val appId = insertApp(database, "shop")
        database.close()

        testApplication {
            application { module(devConfig(dataDir)) }

            val response = client.get("/api/apps/$appId/sessions?anonymous=true&page=0") { allowedHost() }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
        database = SqliteDatabase.open(dataDir)
    }

    @Test
    fun `GET for an unknown app id gets 404`() = runBlocking {
        database.close()

        testApplication {
            application { module(devConfig(dataDir)) }

            val response = client.get("/api/apps/999999/sessions?anonymous=true") { allowedHost() }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
        database = SqliteDatabase.open(dataDir)
    }

    @Test
    fun `GET answers with Cache-Control no-store and no CORS header`() = runBlocking {
        val appId = insertApp(database, "shop")
        insertEvent(database, appId, "e1", "s1", userId = null, element = "octo:session-start", kind = 1)
        database.close()

        testApplication {
            application { module(devConfig(dataDir)) }

            val response = client.get("/api/apps/$appId/sessions?anonymous=true") { allowedHost() }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
        }
        database = SqliteDatabase.open(dataDir)
    }

    @Test
    fun `a wrong Host header gets 403 on this route too`() = runBlocking {
        database.close()

        testApplication {
            application { module(devConfig(dataDir)) }

            val response = client.get("/api/apps/1/sessions?anonymous=true") {
                header(HttpHeaders.Host, "evil.example")
            }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
        database = SqliteDatabase.open(dataDir)
    }

    // D15: the captured log holds no session id, no query string, and no
    // raw path from a log call of this route.
    @Test
    fun `the captured log holds no session id, no query string, and no raw path`() = runBlocking {
        val appId = insertApp(database, "shop")
        insertEvent(
            database, appId, "e1", "the-secret-session-id", userId = null,
            element = "octo:session-start", kind = 1, path = "/the-secret-path",
        )
        database.close()

        testApplication {
            application { module(devConfig(dataDir)) }

            val (response, events) = captureLogEvents {
                client.get(
                    "/api/apps/$appId/sessions?anonymous=true&firstPath=" + "/the-secret-path".encodeURLQueryComponent(),
                ) { allowedHost() }
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val joinedMessages = events.joinToString("\n") { it.formattedMessage }
            assertEquals(false, joinedMessages.contains("the-secret-session-id"))
            assertEquals(false, joinedMessages.contains("the-secret-path"))
            assertEquals(false, joinedMessages.contains("firstPath="))
            assertEquals(false, joinedMessages.contains("anonymous="))
        }
        database = SqliteDatabase.open(dataDir)
    }
}

private fun collectPlanLines(result: ResultSet): List<String> {
    val lines = mutableListOf<String>()
    while (result.next()) {
        lines += result.getString("detail")
    }
    return lines
}

private suspend fun successRows(database: SqliteDatabase, appId: Long): List<SessionRow> {
    val result = loadAnonymousSessions(database, appId, firstPath = null, requestedPage = 1)
    check(result is SessionsResult.Success) { "expected Success, got $result" }
    return result.rows
}

private suspend fun insertApp(database: SqliteDatabase, name: String): Long =
    database.write { writer ->
        writer.prepareStatement(
            "INSERT INTO app (name, database_name, collection_name, created_at) VALUES (?, ?, ?, ?)",
        ).use { insert ->
            insert.setString(1, name)
            insert.setString(2, "db")
            insert.setString(3, "octometer_events")
            insert.setLong(4, 1_700_000_000_000L)
            insert.executeUpdate()
        }
        writer.createStatement().use { statement ->
            statement.executeQuery("SELECT last_insert_rowid()").use { result ->
                result.next()
                result.getLong(1)
            }
        }
    }

private suspend fun insertEvent(
    database: SqliteDatabase,
    appId: Long,
    eventId: String,
    sessionId: String,
    userId: String?,
    element: String,
    kind: Int,
    ts: Long = 1_700_000_000_000L,
    path: String? = null,
    referrerHost: String? = null,
) = database.write { writer ->
    writer.prepareStatement(
        "INSERT INTO event (app_id, event_id, ts, element, session_id, user_id, path, referrer_host, kind) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
    ).use { insert ->
        insert.setLong(1, appId)
        insert.setString(2, eventId)
        insert.setLong(3, ts)
        insert.setString(4, element)
        insert.setString(5, sessionId)
        insert.setString(6, userId)
        insert.setString(7, path)
        insert.setString(8, referrerHost)
        insert.setInt(9, kind)
        insert.executeUpdate()
    }
}
