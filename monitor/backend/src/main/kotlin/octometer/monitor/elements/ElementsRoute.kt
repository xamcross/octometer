package octometer.monitor.elements

import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.sql.Connection
import java.sql.Types
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import octometer.monitor.ErrorBody
import octometer.monitor.store.SqliteDatabase

/**
 * One row of `GET /api/apps/{appId}/elements` (D13, level 3 of the
 * design). `lastInteractionAt` is a UTC ISO 8601 string with
 * milliseconds.
 */
@Serializable
data class ElementRow(
    val element: String,
    val clicks: Long,
    val sessions: Long,
    val lastInteractionAt: String,
)

/** The one response body of the route. D13 names the field `rows`. */
@Serializable
data class ElementsResponse(val rows: List<ElementRow>)

/** The one filter of a request. Exactly one form reaches this type. */
internal sealed interface ElementsFilter {
    data class ByUser(val userId: String) : ElementsFilter
    data object Anonymous : ElementsFilter
    data class BySession(val sessionId: String) : ElementsFilter
}

internal sealed interface ElementsResult {
    data class Success(val rows: List<ElementRow>) : ElementsResult
    data object AppNotFound : ElementsResult
}

private const val APP_ID_MESSAGE = "The app id must be a whole number."
private const val APP_NOT_FOUND_MESSAGE = "The app is not registered."
private const val FILTER_MESSAGE = "Send exactly one of userId, anonymous=true, or sessionId."

// Issue #113, step 5: sessionId must have the UUID form, else the
// request gets 400. This function reads no part of the value into a
// log line or an error body; it only reports true or false.
private val SESSION_ID_PATTERN =
    Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

private fun isUuidForm(value: String): Boolean = SESSION_ID_PATTERN.matches(value)

// Rule M4 of the design (D14, section 6, issue #109): the literal
// `kind = 0` keeps the partial index `event_agg`. A statement without
// the `kind` filter falls to the index `event_session`. Rule M4 keeps
// the literal in the SQL text for that reason. Visibility: internal, so
// the test of the query plan can reuse the exact text that the route
// runs.
internal const val ELEMENT_TOTALS_SQL =
    "SELECT element, COUNT(*) AS clicks, COUNT(DISTINCT session_id) AS sessions, " +
        "MAX(ts) AS last_interaction FROM event " +
        "WHERE app_id = ? AND user_id IS ? AND kind = 0 " +
        "GROUP BY element ORDER BY clicks DESC, element ASC"

// Issue #113, step 5: the third filter, sessionId. The statement holds
// the literal kind = 0 (rule M4) and groups by element, the same shape
// as ELEMENT_TOTALS_SQL. The index event_session (app_id, session_id,
// element, ts, user_id, kind) covers the two leading equality terms
// app_id and session_id, and its third column, element, then answers
// the GROUP BY with no extra sort step.
internal const val ELEMENT_TOTALS_BY_SESSION_SQL =
    "SELECT element, COUNT(*) AS clicks, COUNT(DISTINCT session_id) AS sessions, " +
        "MAX(ts) AS last_interaction FROM event " +
        "WHERE app_id = ? AND session_id = ? AND kind = 0 " +
        "GROUP BY element ORDER BY clicks DESC, element ASC"

private const val APP_EXISTS_SQL = "SELECT 1 FROM app WHERE id = ?"

private val TIMESTAMP_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/** Installs `GET /api/apps/{appId}/elements` on the route tree. */
fun Route.elementsRoute(database: SqliteDatabase) {
    get("/api/apps/{appId}/elements") {
        val appId = call.parameters["appId"]?.toLongOrNull()
        if (appId == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody(APP_ID_MESSAGE))
            return@get
        }
        val filter = parseFilter(call.request.queryParameters)
        if (filter == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody(FILTER_MESSAGE))
            return@get
        }
        when (val result = loadElementTotals(database, appId, filter)) {
            is ElementsResult.Success -> call.respond(ElementsResponse(result.rows))
            ElementsResult.AppNotFound -> call.respond(HttpStatusCode.NotFound, ErrorBody(APP_NOT_FOUND_MESSAGE))
        }
    }
}

/**
 * Reads the app row and the level 3 statement of section 6, and merges
 * them. One `read` call holds the read-only connection for the two
 * statements; no connection escapes this function. Pull request #127
 * made `SqliteDatabase.read { }` one read transaction, so the app check
 * and the level 3 statement now see one snapshot of the store.
 */
internal suspend fun loadElementTotals(
    database: SqliteDatabase,
    appId: Long,
    filter: ElementsFilter,
): ElementsResult =
    database.read { reader ->
        if (!appExists(reader, appId)) {
            ElementsResult.AppNotFound
        } else {
            ElementsResult.Success(readElementRows(reader, appId, filter))
        }
    }

// Exactly one of the three parameters of D13 must reach this function:
// `userId`, `anonymous`, or `sessionId` (issue #113 adds the third).
// A request with zero, or with two or three of them, gets 400. A
// `sessionId` without the UUID form gets 400 too.
//
// MAJOR 2 of the SQL review, and the matching MINOR of the security
// review: this function counts each value, not each key. `Parameters`
// reports one key for `?userId=a&userId=b`, thus a count of the keys
// missed the second value, and the route answered 200 with one of the
// two user ids. A count of the values gives 400 for that request.
internal fun parseFilter(parameters: Parameters): ElementsFilter? {
    val presentValues = listOf("userId", "anonymous", "sessionId")
        .sumOf { key -> parameters.getAll(key)?.size ?: 0 }
    if (presentValues != 1) return null
    return when {
        parameters.contains("sessionId") -> {
            val sessionId = parameters["sessionId"]
            if (sessionId != null && isUuidForm(sessionId)) ElementsFilter.BySession(sessionId) else null
        }
        parameters.contains("anonymous") ->
            if (parameters["anonymous"] == "true") ElementsFilter.Anonymous else null
        else -> {
            val userId = parameters["userId"]
            if (userId.isNullOrBlank()) null else ElementsFilter.ByUser(userId)
        }
    }
}

private fun appExists(connection: Connection, appId: Long): Boolean =
    connection.prepareStatement(APP_EXISTS_SQL).use { statement ->
        statement.setLong(1, appId)
        statement.executeQuery().use { it.next() }
    }

private fun readElementRows(connection: Connection, appId: Long, filter: ElementsFilter): List<ElementRow> {
    val sql = if (filter is ElementsFilter.BySession) ELEMENT_TOTALS_BY_SESSION_SQL else ELEMENT_TOTALS_SQL
    return connection.prepareStatement(sql).use { statement ->
        statement.setLong(1, appId)
        when (filter) {
            is ElementsFilter.ByUser -> statement.setString(2, filter.userId)
            ElementsFilter.Anonymous -> statement.setNull(2, Types.VARCHAR)
            is ElementsFilter.BySession -> statement.setString(2, filter.sessionId)
        }
        statement.executeQuery().use { result ->
            val rows = mutableListOf<ElementRow>()
            while (result.next()) {
                rows += ElementRow(
                    element = result.getString("element"),
                    clicks = result.getLong("clicks"),
                    sessions = result.getLong("sessions"),
                    lastInteractionAt = TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(result.getLong("last_interaction"))),
                )
            }
            rows
        }
    }
}
