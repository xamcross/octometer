package octometer.monitor.sessions

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import io.ktor.http.encodeURLQueryComponent
import io.ktor.server.testing.testApplication
import java.sql.Connection
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
import org.slf4j.LoggerFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    // BLOCKER 1 of correction round 1 (2026-09-26): a retry can write a
    // second octo:session-start row of one session (design decision
    // D44). This test proves that the page still holds one row for
    // that session, and the count still stays 1, with the values of
    // the earliest start row.
    @Test
    fun `two start rows of one session give one page row and a count of 1, from the earliest one`() = runBlocking {
        val appId = insertApp(database, "shop")
        insertEvent(
            database, appId, "e1", "dup", userId = null, element = "octo:session-start", kind = 1,
            path = "/home", referrerHost = "ref.example", ts = 1_700_000_000_100L,
        )
        insertEvent(
            database, appId, "e2", "dup", userId = null, element = "octo:session-start", kind = 1,
            path = "/home", referrerHost = "other.example", ts = 1_700_000_000_101L,
        )
        insertEvent(database, appId, "e3", "dup", userId = null, element = "checkout.save", kind = 0, ts = 1_700_000_000_200L)

        val count = database.read { reader -> countSessions(reader, appId, firstPath = null) }
        val pageRows = database.read { reader -> readSessionsPage(reader, appId, firstPath = null, page = 1) }
        val result = loadAnonymousSessions(database, appId, firstPath = null, requestedPage = 1)
        check(result is SessionsResult.Success)

        assertEquals(1, count, "the count statement must count the session once")
        assertEquals(1, pageRows.size, "the page statement must give one row for the session")
        assertEquals(1, result.pageCount)
        val row = result.rows.single()
        assertEquals("dup", row.sessionId)
        assertEquals("ref.example", row.source, "the earlier start row wins the tie")
        assertEquals("2023-11-14T22:13:20.100Z", row.startTime, "the earlier start row wins the tie")
        assertEquals(1L, row.clicks)
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

    // BLOCKER 2 of correction round 1 (2026-09-26): the plan of the
    // sessions route must come from the four statements that
    // countSessions and readSessionsPage execute, never from a bare
    // subquery text that no code path runs. Each plan below must show
    // an index, and none must show a full scan of the event table.
    @Test
    fun `EXPLAIN QUERY PLAN of each executed statement shows an index, never a full scan of event`() = runBlocking {
        val appId = insertApp(database, "shop")
        insertEvent(database, appId, "e1", "s1", userId = null, element = "octo:session-start", kind = 1, path = "/a")
        insertEvent(database, appId, "e2", "s2", userId = null, element = "checkout.save", kind = 0)

        val plans = database.read { reader ->
            listOf(
                explainCandidatesStatement(reader, SESSION_COUNT_SQL, appId, firstPath = null),
                explainCandidatesStatement(reader, SESSION_COUNT_FILTERED_SQL, appId, firstPath = "/a"),
                explainCandidatesStatement(reader, SESSION_PAGE_SQL, appId, firstPath = null),
                explainCandidatesStatement(reader, SESSION_PAGE_FILTERED_SQL, appId, firstPath = "/a"),
            )
        }

        for (plan in plans) {
            assertTrue(plan.none { it.contains("SCAN event") }, plan.toString())
        }
        assertTrue(
            plans.any { plan -> plan.any { it.contains("INDEX event_start") } },
            "no executed statement used the index event_start: $plans",
        )
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
    // raw path from a log call of this route. MAJOR 1 of the security
    // review of correction round 1 (2026-09-26): the search now reads
    // the formatted message, the raw message, each argument, and the
    // whole throwableProxy chain (the cause and the suppressed list),
    // and it runs a 200, a 400, and a 404 request through one capture.
    @Test
    fun `the captured log holds no session id, no query string, and no raw path, on 200, 400, and 404`() =
        runBlocking {
            val appId = insertApp(database, "shop")
            val sessionIdMarker = "the-secret-session-id"
            val pathMarker = "/the-secret-path"
            insertEvent(
                database, appId, "e1", sessionIdMarker, userId = null,
                element = "octo:session-start", kind = 1, path = pathMarker,
            )
            database.close()

            testApplication {
                application { module(devConfig(dataDir)) }
                val encodedPath = pathMarker.encodeURLQueryComponent()

                lateinit var okStatus: HttpStatusCode
                lateinit var badRequestStatus: HttpStatusCode
                lateinit var notFoundStatus: HttpStatusCode
                val (_, events) = captureLogEvents {
                    okStatus =
                        client.get("/api/apps/$appId/sessions?anonymous=true&firstPath=$encodedPath") {
                            allowedHost()
                        }.status
                    badRequestStatus =
                        client.get("/api/apps/$appId/sessions?anonymous=true&firstPath=$encodedPath&page=bad") {
                            allowedHost()
                        }.status
                    notFoundStatus =
                        client.get("/api/apps/999999/sessions?anonymous=true&firstPath=$encodedPath") {
                            allowedHost()
                        }.status
                }

                assertEquals(HttpStatusCode.OK, okStatus)
                assertEquals(HttpStatusCode.BadRequest, badRequestStatus)
                assertEquals(HttpStatusCode.NotFound, notFoundStatus)
                assertNoLogLineHoldsMarker(events, sessionIdMarker)
                assertNoLogLineHoldsMarker(events, pathMarker)
            }
            database = SqliteDatabase.open(dataDir)
        }

    // MAJOR 1 of the security review of correction round 1: a negative
    // control proves that the sentinel is not vacuous. A marker inside
    // an attached exception must fail the assertion.
    @Test
    fun `the sentinel catches a marker inside an attached exception`() = runBlocking {
        val marker = "the-planted-marker-9f21"
        val log = LoggerFactory.getLogger("octometer.monitor.sessions.SessionsRouteTest")

        val (_, events) = captureLogEvents {
            log.info("a plain line with no marker")
            log.error("an error with an attached cause", RuntimeException(marker))
        }

        val failure = assertFailsWith<AssertionError> { assertNoLogLineHoldsMarker(events, marker) }
        assertTrue(failure.message.orEmpty().contains(marker))
    }
}

private fun collectPlanLines(result: ResultSet): List<String> {
    val lines = mutableListOf<String>()
    while (result.next()) {
        lines += result.getString("detail")
    }
    return lines
}

// Binds the same placeholders that countSessions and readSessionsPage
// bind (BLOCKER 2 of correction round 1): CANDIDATES_APP_ID_COUNT
// copies of appId, then firstPath when the statement is the filtered
// form, then the limit and the offset when the statement carries them.
private fun explainCandidatesStatement(
    reader: Connection,
    sql: String,
    appId: Long,
    firstPath: String?,
): List<String> =
    reader.prepareStatement("EXPLAIN QUERY PLAN $sql").use { statement ->
        var index = 1
        repeat(CANDIDATES_APP_ID_COUNT) { statement.setLong(index++, appId) }
        if (firstPath != null) {
            statement.setString(index++, firstPath)
        }
        if (sql.contains("LIMIT")) {
            statement.setInt(index++, SESSION_PAGE_SIZE)
            statement.setInt(index, 0)
        }
        statement.executeQuery().use { result -> collectPlanLines(result) }
    }

// MAJOR 1 of the security review of correction round 1 (2026-09-26):
// the sentinel searches the formatted message, the raw message, each
// argument, and the whole throwableProxy chain, not the formatted
// message alone. An empty capture is a defect of the test itself, so
// this function asserts against it too.
private fun assertNoLogLineHoldsMarker(events: List<ILoggingEvent>, marker: String) {
    assertTrue(events.isNotEmpty(), "the sentinel captured no log event")
    for (event in events) {
        assertFalse(
            event.formattedMessage.contains(marker),
            "the formatted message held the marker: ${event.formattedMessage}",
        )
        assertFalse(
            event.message?.contains(marker) == true,
            "the raw message held the marker: ${event.message}",
        )
        for (argument in event.argumentArray.orEmpty()) {
            assertFalse(
                argument?.toString()?.contains(marker) == true,
                "an argument held the marker: $argument",
            )
        }
        assertNoThrowableHoldsMarker(event.throwableProxy, marker)
    }
}

// Walks the cause chain and the suppressed list of a throwableProxy.
// Logback prints an attached exception beside the message, thus a
// marker inside a cause or a suppressed exception must fail this
// check too.
private fun assertNoThrowableHoldsMarker(proxy: IThrowableProxy?, marker: String) {
    var current = proxy
    while (current != null) {
        assertFalse(
            current.message?.contains(marker) == true,
            "an exception message held the marker: ${current.message}",
        )
        for (suppressed in current.suppressed.orEmpty()) {
            assertNoThrowableHoldsMarker(suppressed, marker)
        }
        current = current.cause
    }
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
