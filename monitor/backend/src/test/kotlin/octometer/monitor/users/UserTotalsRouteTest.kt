package octometer.monitor.users

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

// Issue #50: the level 2 totals of GET /api/apps/{appId}/users. Source:
// docs/superpowers/specs/2026-09-21-octometer-design.md, D13, D15, and
// section 6.
class UserTotalsRouteTest {

    private val tempDir = Files.createTempDirectory("octometer-user-totals-test-").toFile().also {
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

    // -- Step 1 and step 5: the route, and 404 for an unknown app --------

    @Test
    fun `an unknown app id gives 404 with a fixed sentence`() = runBlocking {
        val result = loadUserTotals(database, appId = 999L, requestedPage = 1, filter = null)

        assertEquals(UserTotalsResult.AppNotFound, result)
    }

    @Test
    fun `GET of an unknown app id gives 404 over HTTP`() = testApplication {
        application { module(devConfig()) }

        val response = client.get("/api/apps/999/users") { allowedHost() }

        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("The app is not registered.", body.getValue("error").jsonPrimitive.content)
    }

    @Test
    fun `a non-numeric app id gives 400, not 500`() = testApplication {
        application { module(devConfig()) }

        val response = client.get("/api/apps/not-a-number/users") { allowedHost() }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // -- Step 2: the level 2 statement, with the literal kind = 0 --------

    @Test
    fun `clicks, sessions, and uniqueElements count only kind = 0 rows, and a session start changes no click count`() =
        runBlocking {
            val appId = insertApp(database, "app-one")
            insertEvent(database, appId, "e1", "alice", "s1", "checkout.save", kind = 0)
            insertEvent(database, appId, "e2", "alice", "s1", "checkout.cancel", kind = 0)
            insertEvent(database, appId, "e3", "alice", "s2", "checkout.save", kind = 0)
            // A session start of a second session for the same user. It must
            // not raise clicks, sessions, or uniqueElements, because the
            // whole statement holds the filter kind = 0 (section 6).
            insertEvent(database, appId, "e4", "alice", "s3", "octo:session-start", kind = 1)

            val rows = (loadUserTotals(database, appId, 1, null) as UserTotalsResult.Success).rows

            val alice = rows.single { it.userId == "alice" }
            assertEquals(3L, alice.clicks, "the session-start row must not count as a click")
            assertEquals(2L, alice.sessions, "the session-start session must not count")
            assertEquals(2L, alice.uniqueElements)
        }

    @Test
    fun `a user with only a session-start row gets no row at all`() = runBlocking {
        val appId = insertApp(database, "app-one")
        insertEvent(database, appId, "e1", "bob", "s1", "octo:session-start", kind = 1)

        val rows = (loadUserTotals(database, appId, 1, null) as UserTotalsResult.Success).rows

        assertTrue(rows.none { it.userId == "bob" }, "a user with zero clicks must not appear")
    }

    @Test
    fun `the NULL user is one row with userId null, and two apps do not mix their users`() = runBlocking {
        val appOne = insertApp(database, "app-one")
        val appTwo = insertApp(database, "app-two")
        insertEvent(database, appOne, "e1", null, "s1", "checkout.save", kind = 0)
        insertEvent(database, appOne, "e2", "alice", "s2", "checkout.save", kind = 0)
        insertEvent(database, appTwo, "e3", "carol", "s3", "checkout.save", kind = 0)

        val rowsOne = (loadUserTotals(database, appOne, 1, null) as UserTotalsResult.Success).rows
        val rowsTwo = (loadUserTotals(database, appTwo, 1, null) as UserTotalsResult.Success).rows

        assertEquals(setOf(null, "alice"), rowsOne.map { it.userId }.toSet())
        assertEquals(listOf("carol"), rowsTwo.map { it.userId })
    }

    @Test
    fun `the order is clicks DESC then user_id ASC`() = runBlocking {
        val appId = insertApp(database, "app-one")
        insertEvent(database, appId, "e1", "bob", "s1", "checkout.save", kind = 0)
        insertEvent(database, appId, "e2", "alice", "s2", "checkout.save", kind = 0)
        insertEvent(database, appId, "e3", "alice", "s2", "checkout.cancel", kind = 0)

        val rows = (loadUserTotals(database, appId, 1, null) as UserTotalsResult.Success).rows

        assertEquals(listOf("alice", "bob"), rows.map { it.userId })
    }

    // MAJOR 1 of the SQL review: two users with the same click count
    // must break the tie on user_id ASC. A mutation to user_id DESC
    // makes this test fail; see the comment below the assertion.
    @Test
    fun `two users with the same click count break the tie on user_id ASC`() = runBlocking {
        val appId = insertApp(database, "app-one")
        insertEvent(database, appId, "e1", "top", "s1", "checkout.save", kind = 0)
        insertEvent(database, appId, "e2", "top", "s1", "checkout.cancel", kind = 0)
        insertEvent(database, appId, "e3", "top", "s1", "checkout.confirm", kind = 0)
        insertEvent(database, appId, "e4", "zeta", "s2", "checkout.save", kind = 0)
        insertEvent(database, appId, "e5", "zeta", "s2", "checkout.cancel", kind = 0)
        insertEvent(database, appId, "e6", "alpha", "s3", "checkout.save", kind = 0)
        insertEvent(database, appId, "e7", "alpha", "s3", "checkout.cancel", kind = 0)

        val rows = (loadUserTotals(database, appId, 1, null) as UserTotalsResult.Success).rows

        // "top" holds 3 clicks. "alpha" and "zeta" each hold 2 clicks,
        // thus only user_id ASC can place "alpha" before "zeta".
        // Changing `user_id ASC` to `user_id DESC` in USER_TOTALS_PAGE_SQL
        // and in USER_TOTALS_PAGE_FILTERED_SQL was seen to fail this
        // assertion (the code was then restored).
        assertEquals(listOf("top", "alpha", "zeta"), rows.map { it.userId })
    }

    // -- Step 3: the filter q, a bound parameter, with an escape ---------

    // MINOR 9 of the SQL review: the earlier name held a literal '%',
    // and the Windows build then printed a warning. This name holds no
    // such character.
    @Test
    fun `q treats the percent sign and the underscore as literal characters, not wildcards`() = runBlocking {
        val appId = insertApp(database, "app-one")
        insertEvent(database, appId, "e1", "50%_off", "s1", "checkout.save", kind = 0)
        // A decoy that a naive, unescaped LIKE would also match, because
        // % and _ would then act as wildcards.
        insertEvent(database, appId, "e2", "50Xoff", "s2", "checkout.save", kind = 0)

        val rows = (loadUserTotals(database, appId, 1, "50%_off") as UserTotalsResult.Success).rows

        assertEquals(listOf("50%_off"), rows.map { it.userId })
    }

    @Test
    fun `q matches no row when the user id holds no such substring`() = runBlocking {
        val appId = insertApp(database, "app-one")
        insertEvent(database, appId, "e1", "alice", "s1", "checkout.save", kind = 0)

        val result = loadUserTotals(database, appId, 1, "zzz") as UserTotalsResult.Success

        assertTrue(result.rows.isEmpty())
        assertEquals(1, result.pageCount)
    }

    // MINOR 6 of the SQL review: a non-empty q drops the row with
    // userId: null, because NULL LIKE '%...%' gives NULL.
    @Test
    fun `a non-empty q drops the row with userId null`() = runBlocking {
        val appId = insertApp(database, "app-one")
        insertEvent(database, appId, "e1", null, "s1", "checkout.save", kind = 0)
        insertEvent(database, appId, "e2", "alice", "s2", "checkout.save", kind = 0)

        val unfiltered = (loadUserTotals(database, appId, 1, null) as UserTotalsResult.Success).rows
        val filtered = (loadUserTotals(database, appId, 1, "a") as UserTotalsResult.Success).rows

        assertTrue(unfiltered.any { it.userId == null }, "the unfiltered page still shows the anonymous row")
        assertTrue(filtered.none { it.userId == null }, "a non-empty q must drop the anonymous row")
        assertEquals(listOf("alice"), filtered.map { it.userId })
    }

    // MINOR 5 of the SQL review: SQLite folds the case of LIKE for an
    // ASCII letter only.
    @Test
    fun `q matches an ASCII letter of a different case, but not a Cyrillic letter of a different case`() =
        runBlocking {
            val appId = insertApp(database, "app-one")
            insertEvent(database, appId, "e1", "alice", "s1", "checkout.save", kind = 0)
            insertEvent(database, appId, "e2", "Алиса", "s2", "checkout.save", kind = 0)

            val asciiMatch = (loadUserTotals(database, appId, 1, "ALICE") as UserTotalsResult.Success).rows
            val cyrillicMiss = (loadUserTotals(database, appId, 1, "алиса") as UserTotalsResult.Success).rows

            assertEquals(listOf("alice"), asciiMatch.map { it.userId })
            assertTrue(cyrillicMiss.isEmpty(), "a Cyrillic letter of a different case must not match")
        }

    // -- Step 4: pages of 50 rows, with page and pageCount ----------------

    @Test
    fun `more than one page gives page 1 with 50 rows and the right pageCount`() = runBlocking {
        val appId = insertApp(database, "app-one")
        repeat(60) { index ->
            val userId = "user-%02d".format(index)
            insertEvent(database, appId, "e$index", userId, "s$index", "checkout.save", kind = 0)
        }

        val result = loadUserTotals(database, appId, 1, null) as UserTotalsResult.Success

        assertEquals(1, result.page)
        assertEquals(2, result.pageCount)
        assertEquals(50, result.rows.size)
    }

    @Test
    fun `page 2 gives the remaining 10 rows`() = runBlocking {
        val appId = insertApp(database, "app-one")
        repeat(60) { index ->
            val userId = "user-%02d".format(index)
            insertEvent(database, appId, "e$index", userId, "s$index", "checkout.save", kind = 0)
        }

        val result = loadUserTotals(database, appId, 2, null) as UserTotalsResult.Success

        assertEquals(2, result.page)
        assertEquals(2, result.pageCount)
        assertEquals(10, result.rows.size)
    }

    @Test
    fun `a page above the last page is clamped to the last page`() = runBlocking {
        val appId = insertApp(database, "app-one")
        repeat(60) { index ->
            val userId = "user-%02d".format(index)
            insertEvent(database, appId, "e$index", userId, "s$index", "checkout.save", kind = 0)
        }

        val result = loadUserTotals(database, appId, 999, null) as UserTotalsResult.Success

        assertEquals(2, result.page)
        assertEquals(10, result.rows.size)
    }

    @Test
    fun `an empty result still gives pageCount 1 and page 1`() = runBlocking {
        val appId = insertApp(database, "app-one")

        val result = loadUserTotals(database, appId, 1, null) as UserTotalsResult.Success

        assertEquals(1, result.page)
        assertEquals(1, result.pageCount)
        assertTrue(result.rows.isEmpty())
    }

    // MAJOR 2 of the SQL review: the count statement must apply q, not
    // only the page statement. 55 of 60 users hold the text "shared" in
    // their user id; 3 of those 55 also hold the rare text "onlythree".
    // A mutation that made the filtered count statement ignore q was
    // seen to fail this test (the code was then restored).
    @Test
    fun `the count statement applies q, thus pageCount reflects the filtered row count`() = runBlocking {
        val appId = insertApp(database, "app-one")
        repeat(55) { index ->
            val marker = if (index < 3) "onlythree" else "shared"
            val userId = "user-$marker-%02d".format(index)
            insertEvent(database, appId, "e$index", userId, "s$index", "checkout.save", kind = 0)
        }
        repeat(5) { index ->
            val userId = "solo-%02d".format(index)
            insertEvent(database, appId, "f$index", userId, "t$index", "checkout.save", kind = 0)
        }

        val unfiltered = loadUserTotals(database, appId, 1, null) as UserTotalsResult.Success
        val sharedFilter = loadUserTotals(database, appId, 1, "shared") as UserTotalsResult.Success
        val narrowFilter = loadUserTotals(database, appId, 1, "onlythree") as UserTotalsResult.Success

        assertEquals(2, unfiltered.pageCount, "60 users need 2 pages")
        assertEquals(2, sharedFilter.pageCount, "55 matching users still need 2 pages")
        assertEquals(1, narrowFilter.pageCount, "3 matching users need 1 page")
        assertEquals(3, narrowFilter.rows.size)
    }

    @Test
    fun `a page of 0 or a negative page gives 400`() = testApplication {
        // The page check runs before the app id ever reaches the store
        // (route order), thus this app id needs no row of its own.
        application { module(devConfig()) }

        val zero = client.get("/api/apps/1/users?page=0") { allowedHost() }
        val negative = client.get("/api/apps/1/users?page=-1") { allowedHost() }
        val notANumber = client.get("/api/apps/1/users?page=abc") { allowedHost() }

        assertEquals(HttpStatusCode.BadRequest, zero.status)
        assertEquals(HttpStatusCode.BadRequest, negative.status)
        assertEquals(HttpStatusCode.BadRequest, notANumber.status)
    }

    // MINOR 3 of the SQL review and MINOR 4 of the security review: an
    // empty page must mean page 1, the same way an empty q means no
    // filter.
    @Test
    fun `an empty page gives page 1, the same way an empty q gives no filter`() = testApplication {
        val (dataDir, appId) = seedApp("app-one") { db, id ->
            insertEvent(db, id, "e1", "alice", "s1", "checkout.save", kind = 0)
        }
        application { module(devConfig(dataDir = dataDir)) }

        val emptyPage = client.get("/api/apps/$appId/users?page=") { allowedHost() }
        val emptyFilter = client.get("/api/apps/$appId/users?q=") { allowedHost() }

        assertEquals(HttpStatusCode.OK, emptyPage.status)
        assertEquals(HttpStatusCode.OK, emptyFilter.status)
        val pageBody = Json.parseToJsonElement(emptyPage.bodyAsText()).jsonObject
        assertEquals(1, pageBody.getValue("page").jsonPrimitive.content.toInt())
        val filterBody = Json.parseToJsonElement(emptyFilter.bodyAsText()).jsonObject
        assertEquals(
            "alice",
            filterBody.getValue("rows").jsonArray.single().jsonObject.getValue("userId").jsonPrimitive.content,
        )
    }

    // MINOR 2 of the security review and the decision of correction
    // round 1: a control character in q gives 400, because a NUL
    // character cuts the LIKE pattern and then matches every user.
    @Test
    fun `a control character in q gives 400 with a fixed sentence`() = testApplication {
        val (dataDir, appId) = seedApp("app-one") { db, id ->
            insertEvent(db, id, "e1", "alice", "s1", "checkout.save", kind = 0)
        }
        application { module(devConfig(dataDir = dataDir)) }

        val nul = client.get("/api/apps/$appId/users?q=" + "a\u0000b".encodeURLQueryComponent()) { allowedHost() }
        val lineBreak = client.get("/api/apps/$appId/users?q=" + "a\nb".encodeURLQueryComponent()) { allowedHost() }
        val tab = client.get("/api/apps/$appId/users?q=" + "a\tb".encodeURLQueryComponent()) { allowedHost() }

        for (response in listOf(nul, lineBreak, tab)) {
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("The filter q holds a control character.", body.getValue("error").jsonPrimitive.content)
        }
    }

    // MINOR 1 of the SQL review: a very long q can make SQLite reject
    // the LIKE pattern. The route now rejects a long q by itself.
    @Test
    fun `a q above 200 characters gives 400 with a fixed sentence`() = testApplication {
        val (dataDir, appId) = seedApp("app-one") { db, id ->
            insertEvent(db, id, "e1", "alice", "s1", "checkout.save", kind = 0)
        }
        application { module(devConfig(dataDir = dataDir)) }

        val tooLong = client.get("/api/apps/$appId/users?q=" + "a".repeat(201)) { allowedHost() }

        assertEquals(HttpStatusCode.BadRequest, tooLong.status)
        val body = Json.parseToJsonElement(tooLong.bodyAsText()).jsonObject
        assertEquals("The filter q is too long.", body.getValue("error").jsonPrimitive.content)
    }

    // Security MAJOR 1: q must stay a bound parameter. Sending SQL
    // syntax through q must not throw, and must not change the event
    // table. Inlining the value into the SQL text of countUserGroups
    // and readUserTotalsPage for a moment was seen to fail this test
    // with a 500 answer (the code was then restored).
    @Test
    fun `a hostile q value is a bound parameter, thus the rows stay unchanged and the answer is 200`() =
        testApplication {
            val (dataDir, appId) = seedApp("app-one") { db, id ->
                insertEvent(db, id, "e1", "alice", "s1", "checkout.save", kind = 0)
            }
            application { module(devConfig(dataDir = dataDir)) }

            val quote = client.get("/api/apps/$appId/users?q=" + "'".encodeURLQueryComponent()) { allowedHost() }
            val dropTable = client.get(
                "/api/apps/$appId/users?q=" + "'; DROP TABLE event; --".encodeURLQueryComponent(),
            ) { allowedHost() }

            assertEquals(HttpStatusCode.OK, quote.status)
            assertEquals(HttpStatusCode.OK, dropTable.status)

            // The event table, and thus the user row, must still exist.
            val after = client.get("/api/apps/$appId/users") { allowedHost() }
            assertEquals(HttpStatusCode.OK, after.status)
            val body = Json.parseToJsonElement(after.bodyAsText()).jsonObject
            assertEquals(
                "alice",
                body.getValue("rows").jsonArray.single().jsonObject.getValue("userId").jsonPrimitive.content,
            )
        }

    // -- Step 6: the HTTP shape, the headers, and the personal data rule --

    @Test
    fun `GET answers with page, pageCount, rows, and Cache-Control no-store, and no CORS header`() =
        testApplication {
            val (dataDir, appId) = seedApp("app-one") { db, id ->
                insertEvent(db, id, "e1", "alice", "s1", "checkout.save", kind = 0)
            }
            application { module(devConfig(dataDir = dataDir)) }

            val response = client.get("/api/apps/$appId/users") { allowedHost() }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(1, body.getValue("page").jsonPrimitive.content.toInt())
            assertEquals(1, body.getValue("pageCount").jsonPrimitive.content.toInt())
            val rows = body.getValue("rows").jsonArray
            assertEquals("alice", rows.single().jsonObject.getValue("userId").jsonPrimitive.content)
        }

    @Test
    fun `a wrong Host header gets 403 on this route too`() = testApplication {
        // The Host check runs before the route handler, thus this app id
        // needs no row of its own.
        application { module(devConfig()) }

        val response = client.get("/api/apps/1/users") { header(HttpHeaders.Host, "evil.example") }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // Security MAJOR 2: the sentinel must read every log level, not
    // only ERROR, else a log line at a lower level could leak the
    // filter text and no test would catch it. Adding a log.info call
    // with the value of q to the route handler, for a moment, was seen
    // to fail this test (the code was then restored).
    @Test
    fun `the captured log holds no line with the filter text, at every log level`() = testApplication {
        val (dataDir, appId) = seedApp("app-one") { db, id ->
            insertEvent(db, id, "e1", "alice", "s1", "checkout.save", kind = 0)
        }
        application { module(devConfig(dataDir = dataDir)) }

        val (response, events) = captureLogEvents {
            client.get("/api/apps/$appId/users?q=alice-secret-id") { allowedHost() }
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val joinedMessages = events.joinToString("\n") { it.formattedMessage }
        assertFalse(joinedMessages.contains("alice-secret-id"))
    }

    @Test
    fun `EXPLAIN QUERY PLAN shows the covering index event_agg for the count and the page statement`() =
        runBlocking {
            val appId = insertApp(database, "app-one")
            insertEvent(database, appId, "e1", "alice", "s1", "checkout.save", kind = 0)
            insertEvent(database, appId, "e2", "bob", "s2", "checkout.save", kind = 1)

            val countPlan = explainPlan(database, USER_TOTALS_COUNT_SQL)
            val pagePlan = explainPlan(database, USER_TOTALS_PAGE_SQL)
            val countFilteredPlan = explainPlan(database, USER_TOTALS_COUNT_FILTERED_SQL)
            val pageFilteredPlan = explainPlan(database, USER_TOTALS_PAGE_FILTERED_SQL)

            assertTrue(countPlan.any { it.contains("COVERING INDEX event_agg") }, countPlan.toString())
            assertTrue(pagePlan.any { it.contains("COVERING INDEX event_agg") }, pagePlan.toString())
            assertTrue(countFilteredPlan.any { it.contains("COVERING INDEX event_agg") }, countFilteredPlan.toString())
            assertTrue(pageFilteredPlan.any { it.contains("COVERING INDEX event_agg") }, pageFilteredPlan.toString())
        }

    // SQL MAJOR 3: main holds one read transaction in
    // SqliteDatabase.read { } since commit 6154083 (pull request #127).
    // The count statement and the page statement below run inside one
    // read { } block. A write that commits on the writer thread between
    // them must stay invisible to the second statement, because both
    // statements share the one snapshot of the read transaction.
    //
    // "user-extra" gets 5 clicks, well above the 1 click of each of the
    // 60 seeded users. If the page statement saw that row, it would
    // sort first (clicks DESC) and push "user-49" off page 1. The
    // expected page 1 is exactly "user-00" .. "user-49".
    @Test
    fun `a committed insert between the count statement and the page statement changes neither result`() =
        runBlocking {
            val appId = insertApp(database, "app-one")
            repeat(60) { index ->
                val userId = "user-%02d".format(index)
                insertEvent(database, appId, "e$index", userId, "s$index", "checkout.save", kind = 0)
            }
            val expectedPage = (0 until 50).map { index -> "user-%02d".format(index) }

            val (totalBeforeInsert, pageRows) = database.read { reader ->
                val total = countUserGroups(reader, appId, null)
                // This write runs on the writer thread and commits while
                // the read transaction above is still open.
                runBlocking {
                    repeat(5) { clickIndex ->
                        insertEvent(
                            database, appId, "e-extra-$clickIndex", "user-extra", "s-extra",
                            "checkout.save", kind = 0,
                        )
                    }
                }
                val rows = readUserTotalsPage(reader, appId, null, page = 1)
                total to rows
            }

            assertEquals(60, totalBeforeInsert, "the count statement must not see the row that commits later")
            assertEquals(
                expectedPage,
                pageRows.map { it.userId },
                "the page statement must not see the row that commits between the two statements",
            )
        }
}

// A route test needs the same store that module() opens from
// config.dataDir. This helper opens its own database on a fresh data
// folder. It seeds the folder, then closes the database. module() can
// then open that same folder, with only one connection open at a time.
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

private suspend fun insertEvent(
    database: SqliteDatabase,
    appId: Long,
    eventId: String,
    userId: String?,
    sessionId: String,
    element: String,
    kind: Int,
) = database.write { writer ->
    writer.prepareStatement(
        "INSERT INTO event (app_id, event_id, ts, element, session_id, user_id, kind) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
    ).use { insert ->
        insert.setLong(1, appId)
        insert.setString(2, eventId)
        insert.setLong(3, 1_700_000_000_000L)
        insert.setString(4, element)
        insert.setString(5, sessionId)
        insert.setString(6, userId)
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
// SQLite plans a LIKE search and a LIMIT clause by the shape of the
// statement, not by the bound value.
private fun bindPlaceholderValues(statement: java.sql.PreparedStatement, sql: String) {
    var index = 1
    statement.setLong(index++, 1L)
    if (sql.contains("LIKE")) {
        statement.setString(index++, "%test%")
    }
    if (sql.contains("LIMIT")) {
        statement.setInt(index++, 50)
        statement.setInt(index, 0)
    }
}
