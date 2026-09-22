package octometer.monitor.apps

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import octometer.monitor.allowedHost
import octometer.monitor.devConfig
import octometer.monitor.module
import octometer.monitor.registerTempRoot
import octometer.monitor.store.SqliteDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Issue #18: the level 1 totals of GET /api/apps. Source:
// docs/superpowers/specs/2026-09-21-octometer-design.md, D8, D13, D14,
// section 6, section 10. The literal `kind = 0` and the two indexes
// (event_agg, event_session) come from the review of #109 on pull
// request #121.
class AppTotalsRouteTest {

    // SQLite MAJOR 1 of correction round 1: dataDir sits under root, so
    // the sibling backups folder of issue #55 stays inside root.
    private val root = Files.createTempDirectory("octometer-app-totals-test-").toFile().also { registerTempRoot(it) }
    private val dataDir = File(root, "data")
    private lateinit var database: SqliteDatabase

    @BeforeTest
    fun setUp() = runBlocking {
        database = SqliteDatabase.open(dataDir.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun `an app without events returns zeros and NEVER_POLLED`() = runBlocking {
        insertApp(database, name = "solo-app")

        val rows = loadAppTotals(database)

        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("solo-app", row.name)
        assertEquals(0L, row.clicks)
        assertEquals(0L, row.uniqueUsers)
        assertEquals(0L, row.uniqueSessions)
        assertEquals("NEVER_POLLED", row.status)
        assertNull(row.lastSuccessAt)
        assertNull(row.lastError)
        assertNull(row.nextPollAt)
    }

    @Test
    fun `two apps with NULL user ids give the right clicks, unique users, and unique sessions`() =
        runBlocking {
            val appOne = insertApp(database, name = "app-one")
            val appTwo = insertApp(database, name = "app-two")

            // app-one: 2 clicks in one session, one of them with no user id.
            insertEvent(database, appOne, eventId = "e1", sessionId = "s1", userId = "user-1", kind = 0)
            insertEvent(database, appOne, eventId = "e2", sessionId = "s1", userId = null, kind = 0)
            // app-two: 1 click, its own session.
            insertEvent(database, appTwo, eventId = "e3", sessionId = "s2", userId = "user-2", kind = 0)

            val rows = loadAppTotals(database).associateBy { it.name }

            val one = rows.getValue("app-one")
            assertEquals(2L, one.clicks)
            assertEquals(1L, one.uniqueUsers, "COUNT(DISTINCT user_id) ignores the NULL row")
            assertEquals(1L, one.uniqueSessions)

            val two = rows.getValue("app-two")
            assertEquals(1L, two.clicks)
            assertEquals(1L, two.uniqueUsers)
            assertEquals(1L, two.uniqueSessions)
        }

    @Test
    fun `a session-start row does not change clicks or uniqueUsers, but its session counts`() =
        runBlocking {
            val appId = insertApp(database, name = "app-with-start")
            insertEvent(database, appId, eventId = "e1", sessionId = "s1", userId = "user-1", kind = 0)
            // A session start (kind = 1) of a second, click-less session.
            insertEvent(database, appId, eventId = "e2", sessionId = "s2", userId = null, kind = 1)

            val row = loadAppTotals(database).single()

            assertEquals(1L, row.clicks, "the session-start row is not a click")
            assertEquals(1L, row.uniqueUsers, "the session-start row adds no user")
            assertEquals(2L, row.uniqueSessions, "the session without a click still counts")
        }

    @Test
    fun `each row holds the nine fields, and a time field is UTC ISO 8601 with milliseconds`() =
        runBlocking {
            val appId = insertApp(
                database,
                name = "timed-app",
                status = "OK",
                lastPollAt = 1_700_000_000_000L,
                lastSuccessAt = 1_700_000_000_123L,
                lastError = null,
                nextPollAt = 1_700_000_060_000L,
            )
            insertEvent(database, appId, eventId = "e1", sessionId = "s1", userId = "user-1", kind = 0)

            val row = loadAppTotals(database).single()

            assertEquals(appId, row.appId)
            assertEquals("timed-app", row.name)
            assertEquals(1L, row.clicks)
            assertEquals(1L, row.uniqueUsers)
            assertEquals(1L, row.uniqueSessions)
            assertEquals("OK", row.status)
            assertEquals("2023-11-14T22:13:20.123Z", row.lastSuccessAt)
            assertNull(row.lastError)
            assertEquals("2023-11-14T22:14:20.000Z", row.nextPollAt)
        }

    // MINOR 2 of correction round 1: the exact plan line, not a
    // substring check on the joined text, so a future plan with a
    // second line (for example an added "USE TEMP B-TREE") cannot pass
    // by matching one word on each of two different lines.
    //
    // The review of this pull request ran the same three statements on
    // 50 000 rows in 3 apps, with and without ANALYZE, and got the same
    // three plan lines in each run. This test keeps 3 rows, because the
    // plan does not depend on the row count once the table holds rows of
    // both kinds; the review comment on pull request #127 records the
    // full-scale evidence.
    // MINOR 3 of correction round 1: a poll cycle that ran (last_poll_at
    // is not null) but wrote no status is a defect of that cycle. The
    // row must show ERROR, not a silent NEVER_POLLED.
    @Test
    fun `a poll cycle that wrote no status gives ERROR, not NEVER_POLLED`() = runBlocking {
        insertApp(
            database,
            name = "half-written-app",
            status = null,
            lastPollAt = 1_700_000_000_000L,
        )

        val row = loadAppTotals(database).single()

        assertEquals("ERROR", row.status)
    }

    // MINOR 4 of correction round 1: a hostile name and a hostile error
    // text must still leave the body as valid JSON, with the value
    // unchanged, because kotlinx.serialization writes the JSON and this
    // route builds no text by hand.
    @Test
    fun `a hostile name and a hostile lastError stay valid JSON with the value unchanged`() =
        testApplication {
            val hostileName = "a \"quote\", a \\backslash\\, a\ttab, and </script>"
            val hostileError = "\u0007\u0000 a control character and a NUL"
            val appId = insertApp(
                database,
                name = hostileName,
                status = "ERROR",
                lastPollAt = 1_700_000_000_000L,
                lastError = hostileError,
            )
            database.close()

            application { module(devConfig(dataDir = dataDir.absolutePath)) }

            val response = client.get("/api/apps") { allowedHost() }

            assertTrue(
                response.headers[HttpHeaders.ContentType]?.startsWith("application/json") == true,
                "the Content-Type header is application/json",
            )
            val row = Json.parseToJsonElement(response.bodyAsText()).jsonArray.single().jsonObject
            assertEquals(appId, row.getValue("appId").jsonPrimitive.content.toLong())
            assertEquals(hostileName, row.getValue("name").jsonPrimitive.content)
            assertEquals(hostileError, row.getValue("lastError").jsonPrimitive.content)
        }

    @Test
    fun `EXPLAIN QUERY PLAN shows a covering index for each of the three statements`() = runBlocking {
        val appOne = insertApp(database, name = "app-one")
        val appTwo = insertApp(database, name = "app-two")
        insertEvent(database, appOne, eventId = "e1", sessionId = "s1", userId = "user-1", kind = 0)
        insertEvent(database, appOne, eventId = "e2", sessionId = "s1", userId = null, kind = 1)
        insertEvent(database, appTwo, eventId = "e3", sessionId = "s2", userId = "user-2", kind = 0)

        val clicksPlan = explainPlan(database, CLICKS_SQL)
        val uniqueUsersPlan = explainPlan(database, UNIQUE_USERS_SQL)
        val uniqueSessionsPlan = explainPlan(database, UNIQUE_SESSIONS_SQL)

        assertEquals("SCAN event USING COVERING INDEX event_agg", clicksPlan)
        assertEquals("SCAN event USING COVERING INDEX event_agg", uniqueUsersPlan)
        assertEquals("SCAN event USING COVERING INDEX event_session", uniqueSessionsPlan)
    }

    @Test
    fun `GET api-apps answers with the rows and Cache-Control no-store`() = testApplication {
        val appId = insertApp(database, name = "http-app")
        insertEvent(database, appId, eventId = "e1", sessionId = "s1", userId = "user-1", kind = 0)
        database.close()

        application { module(devConfig(dataDir = dataDir.absolutePath)) }

        val response = client.get("/api/apps") { allowedHost() }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
        assertEquals(1, body.size)
        val row = body.single().jsonObject
        assertEquals("http-app", row.getValue("name").jsonPrimitive.content)
        assertEquals(1, row.getValue("clicks").jsonPrimitive.content.toInt())
    }
}

private suspend fun insertApp(
    database: SqliteDatabase,
    name: String,
    status: String? = null,
    lastPollAt: Long? = null,
    lastSuccessAt: Long? = null,
    lastError: String? = null,
    nextPollAt: Long? = null,
): Long =
    database.write { writer ->
        writer.prepareStatement(
            "INSERT INTO app (name, database_name, collection_name, created_at, status, " +
                "last_poll_at, last_success_at, last_error, next_poll_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { insert ->
            insert.setString(1, name)
            insert.setString(2, "db")
            insert.setString(3, "octometer_events")
            insert.setLong(4, 1_700_000_000_000L)
            insert.setString(5, status)
            insert.setNullableLong(6, lastPollAt)
            insert.setNullableLong(7, lastSuccessAt)
            insert.setString(8, lastError)
            insert.setNullableLong(9, nextPollAt)
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
    kind: Int,
) = database.write { writer ->
    writer.prepareStatement(
        "INSERT INTO event (app_id, event_id, ts, element, session_id, user_id, kind) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
    ).use { insert ->
        insert.setLong(1, appId)
        insert.setString(2, eventId)
        // Correction round 1 of issue #59 (MAJOR 3, SQLite review): back
        // to the fixed instant. devConfig() now sets a large retentionDays
        // by default, so the purge at MonitorServices.open() keeps this
        // fixed row.
        insert.setLong(3, 1_700_000_000_000L)
        insert.setString(4, "checkout.save")
        insert.setString(5, sessionId)
        insert.setString(6, userId)
        insert.setInt(7, kind)
        insert.executeUpdate()
    }
}

private suspend fun explainPlan(database: SqliteDatabase, sql: String): String =
    database.read { reader -> explainPlanText(reader, sql) }

// Each of the three statements gives one plan line for a covering index
// scan. A plan of two or more lines is itself a finding, thus this
// helper asserts the single line instead of joining a list.
private fun explainPlanText(connection: Connection, sql: String): String {
    val lines = mutableListOf<String>()
    connection.createStatement().use { statement ->
        statement.executeQuery("EXPLAIN QUERY PLAN $sql").use { result ->
            while (result.next()) {
                lines += result.getString("detail")
            }
        }
    }
    check(lines.size == 1) { "expected one plan line, got $lines" }
    return lines.single()
}

private fun java.sql.PreparedStatement.setNullableLong(index: Int, value: Long?) {
    if (value == null) {
        setNull(index, java.sql.Types.INTEGER)
    } else {
        setLong(index, value)
    }
}
