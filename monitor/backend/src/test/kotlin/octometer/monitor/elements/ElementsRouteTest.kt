package octometer.monitor.elements

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.testing.testApplication
import java.sql.Connection
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import octometer.monitor.allowedHost
import octometer.monitor.captureErrorLogEvents
import octometer.monitor.devConfig
import octometer.monitor.module
import octometer.monitor.store.SqliteDatabase
import octometer.monitor.testDataDir
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Issue #51: the level 3 totals of GET /api/apps/{appId}/elements.
// Source: docs/superpowers/specs/2026-09-21-octometer-design.md, D13,
// D14, D15, and section 6. The literal `kind = 0` and the index
// `event_agg` come from issue #109. The third filter `sessionId` is the
// work of issue #113; a request with that parameter gets 400 here.
class ElementsRouteTest {

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

    // -- The level 3 statement (step 3) -----------------------------------

    @Test
    fun `two users of one app give one row for their shared element, with the right clicks and sessions`() =
        runBlocking {
            val appId = insertApp(database, "shop")
            insertEvent(database, appId, "e1", "s1", "user-1", element = "checkout.save", kind = 0)
            insertEvent(database, appId, "e2", "s2", "user-1", element = "checkout.save", kind = 0)
            insertEvent(database, appId, "e3", "s3", "user-2", element = "checkout.save", kind = 0)

            val userOneRows = successRows(database, appId, ElementsFilter.ByUser("user-1"))
            val row = userOneRows.single()

            assertEquals("checkout.save", row.element)
            assertEquals(2L, row.clicks)
            assertEquals(2L, row.sessions)
        }

    @Test
    fun `a second app never mixes its rows into the first app's totals`() = runBlocking {
        val appOne = insertApp(database, "app-one")
        val appTwo = insertApp(database, "app-two")
        insertEvent(database, appOne, "e1", "s1", "user-1", element = "checkout.save", kind = 0)
        insertEvent(database, appTwo, "e2", "s2", "user-1", element = "checkout.save", kind = 0)
        insertEvent(database, appTwo, "e3", "s2", "user-1", element = "checkout.save", kind = 0)

        val rowsOfAppOne = successRows(database, appOne, ElementsFilter.ByUser("user-1"))
        val rowsOfAppTwo = successRows(database, appTwo, ElementsFilter.ByUser("user-1"))

        assertEquals(1L, rowsOfAppOne.single().clicks)
        assertEquals(2L, rowsOfAppTwo.single().clicks)
    }

    @Test
    fun `anonymous=true reads the rows with user_id IS NULL, and never a named user's rows`() = runBlocking {
        val appId = insertApp(database, "shop")
        insertEvent(database, appId, "e1", "s1", userId = null, element = "banner.close", kind = 0)
        insertEvent(database, appId, "e2", "s2", userId = "user-1", element = "banner.close", kind = 0)

        val anonymousRows = successRows(database, appId, ElementsFilter.Anonymous)

        assertEquals(1L, anonymousRows.single().clicks, "only the row with user_id IS NULL counts")
    }

    @Test
    fun `a session start row (kind = 1) never shows as a row, and octo-session-start never appears`() =
        runBlocking {
            val appId = insertApp(database, "shop")
            insertEvent(database, appId, "e1", "s1", "user-1", element = "checkout.save", kind = 0)
            insertEvent(database, appId, "e2", "s1", "user-1", element = "octo:session-start", kind = 1)

            val rows = successRows(database, appId, ElementsFilter.ByUser("user-1"))

            assertEquals(1, rows.size)
            assertEquals("checkout.save", rows.single().element)
            assertTrue(rows.none { it.element == "octo:session-start" })
        }

    @Test
    fun `lastInteractionAt is the UTC ISO 8601 form of the newest click of the element, with milliseconds`() =
        runBlocking {
            val appId = insertApp(database, "shop")
            insertEvent(database, appId, "e1", "s1", "user-1", element = "checkout.save", kind = 0, ts = 1_700_000_000_000L)
            insertEvent(database, appId, "e2", "s1", "user-1", element = "checkout.save", kind = 0, ts = 1_700_000_000_123L)

            val row = successRows(database, appId, ElementsFilter.ByUser("user-1")).single()

            assertEquals("2023-11-14T22:13:20.123Z", row.lastInteractionAt)
        }

    @Test
    fun `an element with every character of the allowed pattern reads back unchanged`() = runBlocking {
        val appId = insertApp(database, "shop")
        val fullPatternElement = "Aa9_.:-Zz0"
        insertEvent(database, appId, "e1", "s1", "user-1", element = fullPatternElement, kind = 0)

        val row = successRows(database, appId, ElementsFilter.ByUser("user-1")).single()

        assertEquals(fullPatternElement, row.element)
    }

    @Test
    fun `the order is clicks DESC then element ASC`() = runBlocking {
        val appId = insertApp(database, "shop")
        // "beta" and "alpha" tie on one click each; "gamma" leads with two.
        insertEvent(database, appId, "e1", "s1", "user-1", element = "gamma", kind = 0)
        insertEvent(database, appId, "e2", "s2", "user-1", element = "gamma", kind = 0)
        insertEvent(database, appId, "e3", "s3", "user-1", element = "beta", kind = 0)
        insertEvent(database, appId, "e4", "s4", "user-1", element = "alpha", kind = 0)

        val rows = successRows(database, appId, ElementsFilter.ByUser("user-1"))

        assertEquals(listOf("gamma", "alpha", "beta"), rows.map { it.element })
    }

    @Test
    fun `a user id with a quote character stays a bound parameter, and never breaks the statement`() =
        runBlocking {
            val appId = insertApp(database, "shop")
            val hostileUserId = "o'brien\"; DROP TABLE event; --"
            insertEvent(database, appId, "e1", "s1", hostileUserId, element = "checkout.save", kind = 0)
            insertEvent(database, appId, "e2", "s2", "user-1", element = "checkout.save", kind = 0)

            val rows = successRows(database, appId, ElementsFilter.ByUser(hostileUserId))

            assertEquals(1L, rows.single().clicks)
            val tableStillExists = database.read { reader ->
                reader.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM event").use { it.next() }
                }
            }
            assertTrue(tableStillExists, "the event table survives a hostile user id value")
        }

    @Test
    fun `an unknown app id gives AppNotFound`() = runBlocking {
        val result = loadElementTotals(database, appId = 999_999L, filter = ElementsFilter.ByUser("user-1"))

        assertEquals(ElementsResult.AppNotFound, result)
    }

    // The row scan of the level 3 statement uses the covering index
    // event_agg, on the two leading equality terms app_id and user_id.
    // SQLite still adds two more steps, because it cannot reuse the same
    // index for a DISTINCT aggregate or for a sort on the aggregate
    // clicks: "USE TEMP B-TREE FOR count(DISTINCT)" and
    // "USE TEMP B-TREE FOR ORDER BY". Both steps run on the small,
    // already-filtered row set of one app and one user, not on the whole
    // table, so the covering index still does the heavy filter work.
    @Test
    fun `EXPLAIN QUERY PLAN shows the covering index event_agg for the level 3 statement`() = runBlocking {
        val appId = insertApp(database, "shop")
        insertEvent(database, appId, "e1", "s1", "user-1", element = "checkout.save", kind = 0)

        val plan = database.read { reader -> explainPlanLines(reader, ELEMENT_TOTALS_SQL) }

        assertEquals(
            listOf(
                "SEARCH event USING COVERING INDEX event_agg (app_id=? AND user_id=?)",
                "USE TEMP B-TREE FOR count(DISTINCT)",
                "USE TEMP B-TREE FOR ORDER BY",
            ),
            plan,
        )
    }

    // -- The filter rule (step 2) ------------------------------------------

    @Test
    fun `userId alone is a valid filter`() {
        assertEquals(ElementsFilter.ByUser("user-1"), parseFilter(paramsOf("userId" to "user-1")))
    }

    @Test
    fun `anonymous=true alone is a valid filter`() {
        assertEquals(ElementsFilter.Anonymous, parseFilter(paramsOf("anonymous" to "true")))
    }

    @Test
    fun `no parameter is invalid`() {
        assertEquals(null, parseFilter(paramsOf()))
    }

    @Test
    fun `userId and anonymous together are invalid`() {
        assertEquals(null, parseFilter(paramsOf("userId" to "user-1", "anonymous" to "true")))
    }

    @Test
    fun `a blank userId is invalid`() {
        assertEquals(null, parseFilter(paramsOf("userId" to "")))
    }

    @Test
    fun `anonymous=false is invalid`() {
        assertEquals(null, parseFilter(paramsOf("anonymous" to "false")))
    }

    @Test
    fun `sessionId alone is invalid, because issue #113 owns that filter`() {
        assertEquals(null, parseFilter(paramsOf("sessionId" to "11111111-1111-1111-1111-111111111111")))
    }

    // -- The HTTP layer (steps 1, 2, 5) -------------------------------------

    @Test
    fun `GET with userId answers 200 with the rows field and the four JSON names`() = runBlocking {
        val appId = insertApp(database, "shop")
        insertEvent(database, appId, "e1", "s1", "user-1", element = "checkout.save", kind = 0)
        database.close()

        testApplication {
            application { module(devConfig(dataDir)) }

            val response = client.get("/api/apps/$appId/elements?userId=user-1") { allowedHost() }

            assertEquals(HttpStatusCode.OK, response.status)
            val rows = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("rows").jsonArray
            val row = rows.single().jsonObject
            assertEquals("checkout.save", row.getValue("element").jsonPrimitive.content)
            assertEquals(1, row.getValue("clicks").jsonPrimitive.content.toInt())
            assertEquals(1, row.getValue("sessions").jsonPrimitive.content.toInt())
            assertTrue(row.containsKey("lastInteractionAt"))
        }
        database = SqliteDatabase.open(dataDir)
    }

    @Test
    fun `GET with no filter gets 400, not 500, and writes no ERROR log line`() = runBlocking {
        val appId = insertApp(database, "shop")
        database.close()

        testApplication {
            application { module(devConfig(dataDir)) }

            val (response, errorEvents) = captureErrorLogEvents {
                client.get("/api/apps/$appId/elements") { allowedHost() }
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(errorEvents.isEmpty(), "a request with no filter must write no ERROR log line")
        }
        database = SqliteDatabase.open(dataDir)
    }

    @Test
    fun `GET for an unknown app id gets 404`() = runBlocking {
        database.close()

        testApplication {
            application { module(devConfig(dataDir)) }

            val response = client.get("/api/apps/999999/elements?anonymous=true") { allowedHost() }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
        database = SqliteDatabase.open(dataDir)
    }

    @Test
    fun `GET with a non-numeric app id gets 400`() = runBlocking {
        database.close()

        testApplication {
            application { module(devConfig(dataDir)) }

            val response = client.get("/api/apps/not-a-number/elements?anonymous=true") { allowedHost() }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
        database = SqliteDatabase.open(dataDir)
    }

    // MINOR: D15 forbids a user id in the captured log. A request that
    // carries one in the query string must still write no such line.
    @Test
    fun `a request with a userId query string writes no log line that holds that value`() = runBlocking {
        val appId = insertApp(database, "shop")
        insertEvent(database, appId, "e1", "s1", "the-secret-user-id", element = "checkout.save", kind = 0)
        database.close()

        testApplication {
            application { module(devConfig(dataDir)) }

            val (_, errorEvents) = captureErrorLogEvents {
                client.get("/api/apps/$appId/elements?userId=the-secret-user-id") { allowedHost() }
            }

            assertTrue(errorEvents.none { it.formattedMessage.contains("the-secret-user-id") })
        }
        database = SqliteDatabase.open(dataDir)
    }
}

private fun paramsOf(vararg pairs: Pair<String, String>): Parameters =
    Parameters.build { for ((key, value) in pairs) append(key, value) }

private suspend fun successRows(database: SqliteDatabase, appId: Long, filter: ElementsFilter): List<ElementRow> {
    val result = loadElementTotals(database, appId, filter)
    check(result is ElementsResult.Success) { "expected Success, got $result" }
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
) = database.write { writer ->
    writer.prepareStatement(
        "INSERT INTO event (app_id, event_id, ts, element, session_id, user_id, kind) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
    ).use { insert ->
        insert.setLong(1, appId)
        insert.setString(2, eventId)
        insert.setLong(3, ts)
        insert.setString(4, element)
        insert.setString(5, sessionId)
        insert.setString(6, userId)
        insert.setInt(7, kind)
        insert.executeUpdate()
    }
}

// Returns each line of the query plan, in order. The test asserts on the
// full list, so an added or a removed step is a visible change.
private fun explainPlanLines(connection: Connection, sql: String): List<String> {
    val lines = mutableListOf<String>()
    connection.createStatement().use { statement ->
        statement.executeQuery("EXPLAIN QUERY PLAN $sql").use { result ->
            while (result.next()) {
                lines += result.getString("detail")
            }
        }
    }
    return lines
}
