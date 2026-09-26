package octometer.monitor.pages

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLQueryComponent
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.sql.Connection
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import octometer.monitor.allowedHost
import octometer.monitor.captureLogEvents
import octometer.monitor.devConfig
import octometer.monitor.module
import octometer.monitor.registerTempRoot
import octometer.monitor.store.SqliteDatabase
import octometer.monitor.testDataDir
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Issue #112: the first-pages table of GET /api/apps/{appId}/first-pages.
// Source: docs/superpowers/specs/2026-09-21-octometer-design.md, D44,
// section 6, D12, D13.
class FirstPagesRouteTest {

    private val tempDir = Files.createTempDirectory("octometer-first-pages-test-").toFile().also {
        registerTempRoot(it)
    }
    private lateinit var database: SqliteDatabase

    @BeforeTest
    fun setUp() = runBlocking {
        database = SqliteDatabase.open(testDataDir(tempDir))
    }

    @AfterTest
    fun tearDown() {
        database.close()
        tempDir.deleteRecursively()
    }

    // -- Step 5: 404 for an unknown app -----------------------------------

    @Test
    fun `an unknown app id gives 404 with a fixed sentence`() = runBlocking {
        val result = loadFirstPages(database, appId = 999L, requestedPage = 1)

        assertEquals(FirstPagesResult.AppNotFound, result)
    }

    @Test
    fun `GET of an unknown app id gives 404 over HTTP`() = testApplication {
        application { module(devConfig()) }

        val response = client.get("/api/apps/999/first-pages") { allowedHost() }

        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("The app is not registered.", body.getValue("error").jsonPrimitive.content)
    }

    @Test
    fun `a non-numeric app id gives 400, not 500`() = testApplication {
        application { module(devConfig()) }

        val response = client.get("/api/apps/not-a-number/first-pages") { allowedHost() }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // -- Step 2 and step 3: the statement, kind = 1, no COALESCE ---------

    @Test
    fun `each row holds path and sessions, counted with COUNT DISTINCT session_id`() = runBlocking {
        val appId = insertApp(database, "app-one")
        insertEvent(database, appId, "e1", "s1", path = "/pricing", kind = 1)
        insertEvent(database, appId, "e2", "s2", path = "/pricing", kind = 1)
        insertEvent(database, appId, "e3", "s3", path = "/docs", kind = 1)

        val rows = (loadFirstPages(database, appId, 1) as FirstPagesResult.Success).rows

        val pricing = rows.single { it.path == "/pricing" }
        assertEquals(2L, pricing.sessions)
        val docs = rows.single { it.path == "/docs" }
        assertEquals(1L, docs.sessions)
    }

    // Break-and-restore proof (recorded in the pull request text): a
    // second octo:session-start row of one session must not raise the
    // count, because the statement counts COUNT(DISTINCT session_id).
    @Test
    fun `a second session-start row of one session raises no session count`() = runBlocking {
        val appId = insertApp(database, "app-one")
        insertEvent(database, appId, "e1", "s1", path = "/pricing", kind = 1)
        insertEvent(database, appId, "e2", "s1", path = "/pricing", kind = 1)

        val rows = (loadFirstPages(database, appId, 1) as FirstPagesResult.Success).rows

        assertEquals(1L, rows.single { it.path == "/pricing" }.sessions)
    }

    // Correction after the brief review: a click row (kind = 0) raises
    // no session count, because the filter is the literal kind = 1.
    @Test
    fun `a click row raises no session count`() = runBlocking {
        val appId = insertApp(database, "app-one")
        insertEvent(database, appId, "e1", "s1", path = "/pricing", kind = 1)
        insertEvent(database, appId, "e2", "s2", path = "/pricing", kind = 0)

        val rows = (loadFirstPages(database, appId, 1) as FirstPagesResult.Success).rows

        assertEquals(1L, rows.single { it.path == "/pricing" }.sessions, "the click row must not count")
    }

    // D44: the API layer writes "(unknown)" for a NULL path. The SQL
    // holds no COALESCE (step 3); readFirstPagesPage writes the text
    // once the row leaves the database.
    @Test
    fun `a NULL path shows as (unknown)`() = runBlocking {
        val appId = insertApp(database, "app-one")
        insertEvent(database, appId, "e1", "s1", path = null, kind = 1)

        val rows = (loadFirstPages(database, appId, 1) as FirstPagesResult.Success).rows

        assertEquals(listOf(UNKNOWN_PATH), rows.map { it.path })
    }

    @Test
    fun `the SQL text of the page statement holds no COALESCE`() {
        assertFalse(FIRST_PAGES_PAGE_SQL.contains("COALESCE", ignoreCase = true))
    }

    // A path outside a kit's route-pattern list is stored as the literal
    // "/other" by the tracker (D40, contract rule C42). This route reads
    // path as plain text, so that row needs no special case here.
    @Test
    fun `a path that the pattern list does not hold gives the row (other)`() = runBlocking {
        val appId = insertApp(database, "app-one")
        insertEvent(database, appId, "e1", "s1", path = "/other", kind = 1)

        val rows = (loadFirstPages(database, appId, 1) as FirstPagesResult.Success).rows

        assertEquals(listOf("/other"), rows.map { it.path })
    }

    // Break-and-restore proof (recorded in the pull request text): the
    // order is sessions DESC, then path ASC. Two paths with the same
    // session count must break the tie on path ASC.
    @Test
    fun `the order is sessions DESC then path ASC`() = runBlocking {
        val appId = insertApp(database, "app-one")
        insertEvent(database, appId, "e1", "s1", path = "/zeta", kind = 1)
        insertEvent(database, appId, "e2", "s2", path = "/alpha", kind = 1)
        insertEvent(database, appId, "e3", "s3", path = "/alpha", kind = 1)

        val rows = (loadFirstPages(database, appId, 1) as FirstPagesResult.Success).rows

        // "/alpha" holds 2 sessions, above the 1 session of "/zeta", thus
        // sessions DESC alone already orders this pair; a tie is proven
        // by the next test.
        assertEquals(listOf("/alpha", "/zeta"), rows.map { it.path })
    }

    @Test
    fun `two paths with the same session count break the tie on path ASC`() = runBlocking {
        val appId = insertApp(database, "app-one")
        insertEvent(database, appId, "e1", "s1", path = "/zeta", kind = 1)
        insertEvent(database, appId, "e2", "s2", path = "/alpha", kind = 1)
        insertEvent(database, appId, "e3", "s3", path = "/mid", kind = 1)

        val rows = (loadFirstPages(database, appId, 1) as FirstPagesResult.Success).rows

        assertEquals(listOf("/alpha", "/mid", "/zeta"), rows.map { it.path })
    }

    // -- Step 4: pages of 50 rows, with page and pageCount ----------------

    @Test
    fun `more than one page gives page 1 with 50 rows and the right pageCount`() = runBlocking {
        val appId = insertApp(database, "app-one")
        repeat(60) { index ->
            insertEvent(database, appId, "e$index", "s$index", path = "/path-%02d".format(index), kind = 1)
        }

        val result = loadFirstPages(database, appId, 1) as FirstPagesResult.Success

        assertEquals(1, result.page)
        assertEquals(2, result.pageCount)
        assertEquals(50, result.rows.size)
    }

    @Test
    fun `a page above the last page is clamped to the last page`() = runBlocking {
        val appId = insertApp(database, "app-one")
        repeat(60) { index ->
            insertEvent(database, appId, "e$index", "s$index", path = "/path-%02d".format(index), kind = 1)
        }

        val result = loadFirstPages(database, appId, 999) as FirstPagesResult.Success

        assertEquals(2, result.page)
        assertEquals(10, result.rows.size)
    }

    @Test
    fun `an empty result still gives pageCount 1 and page 1`() = runBlocking {
        val appId = insertApp(database, "app-one")

        val result = loadFirstPages(database, appId, 1) as FirstPagesResult.Success

        assertEquals(1, result.page)
        assertEquals(1, result.pageCount)
        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun `a page of 0 or a negative page gives 400`() = testApplication {
        application { module(devConfig()) }

        val zero = client.get("/api/apps/1/first-pages?page=0") { allowedHost() }
        val negative = client.get("/api/apps/1/first-pages?page=-1") { allowedHost() }
        val notANumber = client.get("/api/apps/1/first-pages?page=abc") { allowedHost() }

        assertEquals(HttpStatusCode.BadRequest, zero.status)
        assertEquals(HttpStatusCode.BadRequest, negative.status)
        assertEquals(HttpStatusCode.BadRequest, notANumber.status)
    }

    @Test
    fun `an empty page gives page 1`() = testApplication {
        val (dataDir, appId) = seedApp("app-one") { db, id ->
            insertEvent(db, id, "e1", "s1", path = "/pricing", kind = 1)
        }
        application { module(devConfig(dataDir = dataDir)) }

        val response = client.get("/api/apps/$appId/first-pages?page=") { allowedHost() }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(1, body.getValue("page").jsonPrimitive.content.toInt())
    }

    // The bound-parameter test of the brief (lesson 1 of brief-database,
    // lesson 3 of brief-internal-api): a hostile value must not reach the
    // SQL text. The appId path segment and the page query parameter are
    // each parsed as a number before either statement runs, so a hostile
    // string here must give 400, and the event table must stay unchanged.
    @Test
    fun `a hostile appId or page value is a bound parameter, thus the rows stay unchanged and the answer is 400`() =
        testApplication {
            val (dataDir, appId) = seedApp("app-one") { db, id ->
                insertEvent(db, id, "e1", "s1", path = "/pricing", kind = 1)
            }
            application { module(devConfig(dataDir = dataDir)) }

            val quoteAppId = client.get(
                "/api/apps/" + "'".encodeURLQueryComponent() + "/first-pages",
            ) { allowedHost() }
            val dropTableAppId = client.get(
                "/api/apps/" + "'; DROP TABLE event; --".encodeURLQueryComponent() + "/first-pages",
            ) { allowedHost() }
            val dropTablePage = client.get(
                "/api/apps/$appId/first-pages?page=" + "'; DROP TABLE event; --".encodeURLQueryComponent(),
            ) { allowedHost() }

            assertEquals(HttpStatusCode.BadRequest, quoteAppId.status)
            assertEquals(HttpStatusCode.BadRequest, dropTableAppId.status)
            assertEquals(HttpStatusCode.BadRequest, dropTablePage.status)

            // The event table, and thus the seeded row, must still exist.
            val after = client.get("/api/apps/$appId/first-pages") { allowedHost() }
            assertEquals(HttpStatusCode.OK, after.status)
            val body = Json.parseToJsonElement(after.bodyAsText()).jsonObject
            assertEquals(
                "/pricing",
                body.getValue("rows").jsonArray.single().jsonObject.getValue("path").jsonPrimitive.content,
            )
        }

    // -- Step 6: the HTTP shape, the headers, and the guard ---------------

    @Test
    fun `GET answers with page, pageCount, rows, and Cache-Control no-store, and no CORS header`() =
        testApplication {
            val (dataDir, appId) = seedApp("app-one") { db, id ->
                insertEvent(db, id, "e1", "s1", path = "/pricing", kind = 1)
            }
            application { module(devConfig(dataDir = dataDir)) }

            val response = client.get("/api/apps/$appId/first-pages") { allowedHost() }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(1, body.getValue("page").jsonPrimitive.content.toInt())
            assertEquals(1, body.getValue("pageCount").jsonPrimitive.content.toInt())
            val rows = body.getValue("rows").jsonArray
            assertEquals("/pricing", rows.single().jsonObject.getValue("path").jsonPrimitive.content)
        }

    @Test
    fun `a wrong Host header gets 403 on this route too`() = testApplication {
        application { module(devConfig()) }

        val response = client.get("/api/apps/1/first-pages") { header(HttpHeaders.Host, "evil.example") }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // Correction after the brief review: the captured log holds no query
    // string and no raw path. This route adds no log call of its own; the
    // sentinel proves that fact stays true for each of the two calls that
    // this route makes: the query parameter page, and the SQL layer that
    // reads the row's path column (design lesson 1: a log line holds no
    // path). The CallLogging format of issue #31 decides the request
    // line; this test covers this route's own log calls only, not that
    // format.
    @Test
    fun `the captured log holds no line with the query string or the row path`() = testApplication {
        val (dataDir, appId) = seedApp("app-one") { db, id ->
            insertEvent(db, id, "e1", "s1", path = "/leaked-row-path-marker-789", kind = 1)
        }
        application { module(devConfig(dataDir = dataDir)) }

        val (queryResponse, queryEvents) = captureLogEvents {
            client.get(
                "/api/apps/$appId/first-pages?page=leaked-query-marker-321",
            ) { allowedHost() }
        }
        val (rowResponse, rowEvents) = captureLogEvents {
            client.get("/api/apps/$appId/first-pages") { allowedHost() }
        }

        assertEquals(HttpStatusCode.BadRequest, queryResponse.status)
        assertEquals(HttpStatusCode.OK, rowResponse.status)
        val joinedQueryMessages = queryEvents.joinToString("\n") { it.formattedMessage }
        val joinedRowMessages = rowEvents.joinToString("\n") { it.formattedMessage }
        assertFalse(joinedQueryMessages.contains("leaked-query-marker-321"))
        assertFalse(joinedRowMessages.contains("leaked-row-path-marker-789"))
    }

    // Acceptance criterion: EXPLAIN QUERY PLAN shows the index
    // event_first_page for the count statement and the page statement.
    @Test
    fun `EXPLAIN QUERY PLAN shows the index event_first_page for the count and the page statement`() =
        runBlocking {
            val appId = insertApp(database, "app-one")
            insertEvent(database, appId, "e1", "s1", path = "/pricing", kind = 1)
            insertEvent(database, appId, "e2", "s2", path = "/pricing", kind = 0)

            val countPlan = explainPlan(database, FIRST_PAGES_COUNT_SQL)
            val pagePlan = explainPlan(database, FIRST_PAGES_PAGE_SQL)

            assertTrue(countPlan.any { it.contains("INDEX event_first_page") }, countPlan.toString())
            assertTrue(pagePlan.any { it.contains("INDEX event_first_page") }, pagePlan.toString())
        }

    // SQL MAJOR 3 of the SQL review (issue #50), reused here (correction
    // after the brief review): the count statement and the page statement
    // must run in one database.read { } block, so both read one snapshot.
    // "path-extra" gets 5 sessions, well above the 1 session of each of
    // the 60 seeded paths. If the page statement saw that row, it would
    // sort first (sessions DESC) and push "path-49" off page 1.
    @Test
    fun `a committed insert between the count statement and the page statement changes neither result`() =
        runBlocking {
            val appId = insertApp(database, "app-one")
            repeat(60) { index ->
                insertEvent(database, appId, "e$index", "s$index", path = "/path-%02d".format(index), kind = 1)
            }
            val expectedPage = (0 until 50).map { index -> "/path-%02d".format(index) }

            val (totalBeforeInsert, pageRows) = database.read { reader ->
                val total = countFirstPages(reader, appId)
                // This write runs on the writer thread and commits while
                // the read transaction above is still open.
                runBlocking {
                    repeat(5) { sessionIndex ->
                        insertEvent(
                            database, appId, "e-extra-$sessionIndex", "s-extra-$sessionIndex",
                            path = "/path-extra", kind = 1,
                        )
                    }
                }
                val rows = readFirstPagesPage(reader, appId, page = 1)
                total to rows
            }

            assertEquals(60, totalBeforeInsert, "the count statement must not see the row that commits later")
            assertEquals(
                expectedPage,
                pageRows.map { it.path },
                "the page statement must not see the row that commits between the two statements",
            )
        }
}

// A route test needs the same store that module() opens from
// config.dataDir. This helper opens its own database on a fresh data
// folder, the same pattern as UserTotalsRouteTest.
private fun seedApp(name: String, seed: suspend (SqliteDatabase, Long) -> Unit): Pair<String, Long> {
    val dataDir = testDataDir()
    val db = SqliteDatabase.open(dataDir)
    val appId = try {
        runBlocking {
            val id = insertApp(db, name)
            seed(db, id)
            id
        }
    } finally {
        db.close()
    }
    return dataDir to appId
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

// This copy stays local to this test file (correction after the brief
// review): the parallel route test of issue #113 gets no new shared
// helper from this change. It inserts the path and the kind columns of
// migration 002, since the first-pages statement needs both.
private suspend fun insertEvent(
    database: SqliteDatabase,
    appId: Long,
    eventId: String,
    sessionId: String,
    path: String?,
    kind: Int,
) = database.write { writer ->
    writer.prepareStatement(
        "INSERT INTO event (app_id, event_id, ts, element, session_id, path, kind) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
    ).use { insert ->
        insert.setLong(1, appId)
        insert.setString(2, eventId)
        insert.setLong(3, 1_700_000_000_000L)
        insert.setString(4, if (kind == 1) "octo:session-start" else "checkout.save")
        insert.setString(5, sessionId)
        insert.setString(6, path)
        insert.setInt(7, kind)
        insert.executeUpdate()
    }
}

private suspend fun explainPlan(database: SqliteDatabase, sql: String): List<String> =
    database.read { reader -> explainPlanText(reader, sql) }

private fun explainPlanText(connection: Connection, sql: String): List<String> {
    val lines = mutableListOf<String>()
    connection.prepareStatement("EXPLAIN QUERY PLAN $sql").use { statement ->
        bindPlaceholderValues(statement, sql)
        statement.executeQuery().use { result ->
            while (result.next()) {
                lines += result.getString("detail")
            }
        }
    }
    return lines
}

// EXPLAIN QUERY PLAN still needs a value for each "?" of the statement.
// The page statement holds one placeholder for app_id and one for the
// offset; the count statement holds one placeholder for app_id alone.
private fun bindPlaceholderValues(statement: java.sql.PreparedStatement, sql: String) {
    var index = 1
    statement.setLong(index++, 1L)
    if (sql.contains("OFFSET")) {
        statement.setInt(index, 0)
    }
}
