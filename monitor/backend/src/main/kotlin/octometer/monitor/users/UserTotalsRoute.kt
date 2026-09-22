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

/** The maximum length of the filter `q` (correction round 1 of pull request #145). */
internal const val MAX_FILTER_LENGTH = 200

// The escape character of each LIKE clause below. The bound value of q
// gets this character before a literal '%', a literal '_', and this
// character itself. A user id can then never turn q into a wildcard
// (issue #50, step 3).
private const val LIKE_ESCAPE_CHAR = '\\'

// Design document section 6: "Level 2 and level 3 keep the filter
// kind = 0, else SQLite ignores the index event_agg." A bound parameter
// does not give the same plan on each SQLite build. Thus the filter
// stays in the SQL text. Each statement here holds one fixed shape,
// with the filter q or without it. The value of q always travels as a
// bound parameter of the LIKE clause. No user text ever joins the SQL
// text itself.
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

/**
 * Installs `GET /api/apps/{appId}/users` on the route tree (issue #50).
 *
 * An empty or a missing `page` means page 1. An empty or a missing `q`
 * means no filter. `q` matches a substring of the user id with `LIKE`.
 * SQLite folds the case of `LIKE` for an ASCII letter only; a letter
 * outside ASCII needs the same case in `q` and in the user id. The
 * route adds no `lower()` call, because that call loses the index
 * `event_agg`.
 *
 * A non-empty `q` drops the row with `userId: null`, because SQLite
 * never matches `NULL` against a `LIKE` pattern. An empty `q` still
 * shows that row.
 *
 * A `q` above [MAX_FILTER_LENGTH] characters gives 400. A `q` with a
 * control character gives 400 too: a NUL character cuts the `LIKE`
 * pattern short, and the shortened pattern then matches every user.
 */
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
        if (filter != null && filter.length > MAX_FILTER_LENGTH) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody("The filter q is too long."))
            return@get
        }
        if (filter != null && filter.any { it.code < 0x20 }) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody("The filter q holds a control character."))
            return@get
        }
        when (val result = loadUserTotals(database, appId, requestedPage, filter)) {
            UserTotalsResult.AppNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorBody("The app is not registered."))
            is UserTotalsResult.Success ->
                call.respond(UserTotalsResponse(page = result.page, pageCount = result.pageCount, rows = result.rows))
        }
    }
}

// A missing page or an empty page defaults to 1, the same way an empty
// q means no filter. A present, non-empty page must be a positive whole
// number. Otherwise the route gives 400 (issue #50, step 4, and D13).
private fun parsePage(raw: String?): Int? {
    if (raw.isNullOrEmpty()) return 1
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
 * `main` holds that change since commit 6154083 ("feat(monitor): serve
 * the level 1 totals at GET /api/apps (#127)"): `SqliteDatabase.read { }`
 * runs `BEGIN` before the block and `COMMIT` after it, thus the whole
 * block is one read transaction. The count statement and the page
 * statement then read the one snapshot of that transaction, and a
 * write that commits between them changes neither result.
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

// An empty filter means "no filter". An empty LIKE pattern would still
// match every row. This function keeps the plain SQL text for the
// common case of no q parameter. A non-empty filter drops the row with
// userId: null, because `NULL LIKE '%...%'` gives NULL, and SQLite
// treats that as no match.
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

// internal, not private: the test of SQL MAJOR 3 (correction round 1)
// calls this function directly, to insert a write between the count
// statement and the page statement of one read transaction.
internal fun countUserGroups(reader: Connection, appId: Long, likePattern: String?): Int {
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

internal fun readUserTotalsPage(
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
