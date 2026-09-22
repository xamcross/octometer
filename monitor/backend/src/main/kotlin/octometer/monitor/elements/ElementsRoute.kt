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
}

internal sealed interface ElementsResult {
    data class Success(val rows: List<ElementRow>) : ElementsResult
    data object AppNotFound : ElementsResult
}

private const val APP_ID_MESSAGE = "The app id must be a whole number."
private const val APP_NOT_FOUND_MESSAGE = "The app is not registered."
private const val FILTER_MESSAGE = "Send exactly one of userId or anonymous=true."

// Rule M4 of the design (D14, section 6, issue #109): the literal
// `kind = 0` keeps the partial index `event_agg`. A bound parameter does
// not give the same plan on each SQLite build, so the filter stays in
// the SQL text. Visibility: internal, so the test of the query plan can
// reuse the exact text that the route runs.
internal const val ELEMENT_TOTALS_SQL =
    "SELECT element, COUNT(*) AS clicks, COUNT(DISTINCT session_id) AS sessions, " +
        "MAX(ts) AS last_interaction FROM event " +
        "WHERE app_id = ? AND user_id IS ? AND kind = 0 " +
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
 * statements; no connection escapes this function.
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
// `userId`, `anonymous`, or `sessionId`. Issue #113 owns `sessionId`; a
// request with that parameter gets 400 today, the same as a request
// with zero or with two of the three parameters.
internal fun parseFilter(parameters: Parameters): ElementsFilter? {
    val presentKeys = listOf("userId", "anonymous", "sessionId").count { parameters.contains(it) }
    if (presentKeys != 1) return null
    return when {
        parameters.contains("sessionId") -> null
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

private fun readElementRows(connection: Connection, appId: Long, filter: ElementsFilter): List<ElementRow> =
    connection.prepareStatement(ELEMENT_TOTALS_SQL).use { statement ->
        statement.setLong(1, appId)
        when (filter) {
            is ElementsFilter.ByUser -> statement.setString(2, filter.userId)
            ElementsFilter.Anonymous -> statement.setNull(2, Types.VARCHAR)
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
