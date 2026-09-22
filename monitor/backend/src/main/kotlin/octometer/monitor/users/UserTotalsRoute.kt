package octometer.monitor.users

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.sql.Connection
import kotlinx.serialization.Serializable
import octometer.monitor.ErrorBody
import octometer.monitor.store.SqliteDatabase

/** The row count of one page (issue #50, step 4). */
internal const val USER_PAGE_SIZE = 50

// The escape character of each LIKE clause below. The bound value of q
// gets this character before a literal '%', a literal '_', and this
// character itself. A user id can then never turn q into a wildcard
// (issue #50, step 3).
private const val LIKE_ESCAPE_CHAR = '\\'

// Rule M4 of the design (section 6): the literal `kind = 0` keeps the
// partial index event_agg. A bound parameter does not give the same
// plan on each SQLite build. Thus the filter stays in the SQL text.
// Each statement here holds one fixed shape, with the filter q or
// without it. The value of q always travels as a bound parameter of
// the LIKE clause. No user text ever joins the SQL text itself.
internal const val USER_TOTALS_COUNT_SQL =
    "SELECT COUNT(*) FROM (SELECT user_id FROM event WHERE app_id = ? AND kind = 0 GROUP BY user_id)"
internal const val USER_TOTALS_COUNT_FILTERED_SQL =
    "SELECT COUNT(*) FROM (SELECT user_id FROM event WHERE app_id = ? AND kind = 0 " +
        "AND user_id LIKE ? ESCAPE '\\' GROUP BY user_id)"

// The level 2 statement of section 6: one row for each user_id, grouped
// on the covering index event_agg(app_id, user_id, element, session_id,
// ts). The order clicks DESC, user_id ASC is the fixed order of D13.
internal const val USER_TOTALS_PAGE_SQL =
    "SELECT user_id, COUNT(*) AS clicks, COUNT(DISTINCT session_id) AS sessions, " +
        "COUNT(DISTINCT element) AS unique_elements FROM event WHERE app_id = ? AND kind = 0 " +
        "GROUP BY user_id ORDER BY clicks DESC, user_id ASC LIMIT ? OFFSET ?"
internal const val USER_TOTALS_PAGE_FILTERED_SQL =
    "SELECT user_id, COUNT(*) AS clicks, COUNT(DISTINCT session_id) AS sessions, " +
        "COUNT(DISTINCT element) AS unique_elements FROM event WHERE app_id = ? AND kind = 0 " +
        "AND user_id LIKE ? ESCAPE '\\' GROUP BY user_id ORDER BY clicks DESC, user_id ASC LIMIT ? OFFSET ?"

/** One row of `GET /api/apps/{appId}/users` (D13, level 2 of the design). */
@Serializable
data class UserTotalsRow(
    val userId: String?,
    val clicks: Long,
    val sessions: Long,
    val uniqueElements: Long,
)

/** The page shape of D13: the current page, the last page, and the rows. */
@Serializable
data class UserTotalsResponse(val page: Int, val pageCount: Int, val rows: List<UserTotalsRow>)

sealed class UserTotalsResult {
    data class Success(val page: Int, val pageCount: Int, val rows: List<UserTotalsRow>) : UserTotalsResult()
    object AppNotFound : UserTotalsResult()
}

/** Installs `GET /api/apps/{appId}/users` on the route tree (issue #50). */
fun Route.userTotalsRoute(database: SqliteDatabase) {
    get("/api/apps/{appId}/users") {
        val appId = call.parameters["appId"]?.toLongOrNull()
        if (appId == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody("The app id must be a whole number."))
            return@get
        }
        val requestedPage = parsePage(call.request.queryParameters["page"])
        if (requestedPage == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody("The page must be a positive whole number."))
            return@get
        }
        val filter = call.request.queryParameters["q"]
        when (val result = loadUserTotals(database, appId, requestedPage, filter)) {
            UserTotalsResult.AppNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorBody("The app is not registered."))
            is UserTotalsResult.Success ->
                call.respond(UserTotalsResponse(page = result.page, pageCount = result.pageCount, rows = result.rows))
        }
    }
}

// A missing page defaults to 1. A present page must be a positive whole
// number. Otherwise the route gives 400 (issue #50, step 4, and D13).
private fun parsePage(raw: String?): Int? {
    if (raw == null) return 1
    val page = raw.toIntOrNull() ?: return null
    return page.takeIf { it >= 1 }
}

/**
 * Reads the level 2 rows of one app. It runs the exists check, the
 * count statement, and the page statement in one `database.read { }`
 * block.
 *
 * The count statement and the page statement must read one snapshot.
 * Otherwise a poll-loop write between the two statements can move a
 * user across the page boundary. That gives a wrong pageCount.
 *
 * Pull request #127 (issue #18) turns `read { }` into one read
 * transaction. That change is not on `main` yet. Until it merges, the
 * mutex of `read { }` still keeps the two statements together, with no
 * poll-loop write between them on the one reader connection.
 */
suspend fun loadUserTotals(
    database: SqliteDatabase,
    appId: Long,
    requestedPage: Int,
    filter: String?,
): UserTotalsResult =
    database.read { reader ->
        if (!appExists(reader, appId)) {
            return@read UserTotalsResult.AppNotFound
        }
        val likePattern = toLikePattern(filter)
        val total = countUserGroups(reader, appId, likePattern)
        val pageCount = maxOf(1, ceilDiv(total, USER_PAGE_SIZE))
        val page = requestedPage.coerceAtMost(pageCount)
        val rows = readUserTotalsPage(reader, appId, likePattern, page)
        UserTotalsResult.Success(page = page, pageCount = pageCount, rows = rows)
    }

// A blank filter means "no filter". An empty LIKE pattern would still
// match every row. This function keeps the plain SQL text for the
// common case of no q parameter.
private fun toLikePattern(filter: String?): String? =
    filter?.takeIf { it.isNotEmpty() }?.let { "%" + escapeLikePattern(it) + "%" }

// Escapes '%', '_', and the escape character itself. The LIKE clause
// then treats the whole value of q as one literal substring (issue
// #50, step 3).
internal fun escapeLikePattern(value: String): String =
    buildString {
        for (char in value) {
            if (char == LIKE_ESCAPE_CHAR || char == '%' || char == '_') {
                append(LIKE_ESCAPE_CHAR)
            }
            append(char)
        }
    }

private fun ceilDiv(total: Int, size: Int): Int = (total + size - 1) / size

private fun appExists(reader: Connection, appId: Long): Boolean =
    reader.prepareStatement("SELECT 1 FROM app WHERE id = ?").use { statement ->
        statement.setLong(1, appId)
        statement.executeQuery().use { it.next() }
    }

private fun countUserGroups(reader: Connection, appId: Long, likePattern: String?): Int {
    val sql = if (likePattern == null) USER_TOTALS_COUNT_SQL else USER_TOTALS_COUNT_FILTERED_SQL
    return reader.prepareStatement(sql).use { statement ->
        var index = 1
        statement.setLong(index++, appId)
        if (likePattern != null) {
            statement.setString(index, likePattern)
        }
        statement.executeQuery().use { result ->
            result.next()
            result.getInt(1)
        }
    }
}

private fun readUserTotalsPage(
    reader: Connection,
    appId: Long,
    likePattern: String?,
    page: Int,
): List<UserTotalsRow> {
    val sql = if (likePattern == null) USER_TOTALS_PAGE_SQL else USER_TOTALS_PAGE_FILTERED_SQL
    return reader.prepareStatement(sql).use { statement ->
        var index = 1
        statement.setLong(index++, appId)
        if (likePattern != null) {
            statement.setString(index++, likePattern)
        }
        statement.setInt(index++, USER_PAGE_SIZE)
        statement.setInt(index, (page - 1) * USER_PAGE_SIZE)
        statement.executeQuery().use { result ->
            val rows = mutableListOf<UserTotalsRow>()
            while (result.next()) {
                rows += UserTotalsRow(
                    userId = result.getString(1),
                    clicks = result.getLong(2),
                    sessions = result.getLong(3),
                    uniqueElements = result.getLong(4),
                )
            }
            rows
        }
    }
}
