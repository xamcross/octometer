package octometer.monitor.sessions

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.sql.Connection
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import octometer.monitor.ErrorBody
import octometer.monitor.store.SqliteDatabase

/** The row count of one page (issue #113, design decision D13). */
internal const val SESSION_PAGE_SIZE = 50

private const val DIRECT_SOURCE = "(direct)"
private const val UNKNOWN_PATH = "(unknown)"
private const val APP_ID_MESSAGE = "The app id must be a whole number."
private const val APP_NOT_FOUND_MESSAGE = "The app is not registered."
private const val ANONYMOUS_MESSAGE = "Send anonymous=true. This version allows no other value."
private const val PAGE_MESSAGE = "The page must be a positive whole number."

/**
 * One row of `GET /api/apps/{appId}/sessions` (D13, D44, the anonymous
 * session table).
 *
 * `userId` is the user id of the earliest sign-in of the session (the
 * maintainer's correction of 2026-09-26): the `user_id` value of the
 * event with the smallest `ts` among the events of that session with
 * `user_id IS NOT NULL`. The field is `null` when no event of the
 * session ever carries a user id. One session can hold two users
 * (design section 6); this rule always picks the first one.
 */
@Serializable
data class SessionRow(
    val sessionId: String,
    val firstPath: String,
    val source: String,
    val startTime: String,
    val clicks: Long,
    val userId: String?,
)

/** The page shape of D13: the current page, the last page, and the rows. */
@Serializable
data class SessionsResponse(val page: Int, val pageCount: Int, val rows: List<SessionRow>)

internal sealed class SessionsResult {
    data class Success(val page: Int, val pageCount: Int, val rows: List<SessionRow>) : SessionsResult()
    object AppNotFound : SessionsResult()
}

// Part 1 of the selection rule of D13 (the maintainer's correction of
// 2026-09-26): a session qualifies when it holds a minimum of one event
// with user_id IS NULL. This statement groups every event row of the
// app by session_id, on the index event_session (design section 6, "the
// event_session form"). The HAVING clause keeps a session only when one
// of its rows has user_id IS NULL. The CASE expressions also give the
// click count and the time of the first click, for a session without a
// start row. Rule M4 of the design keeps the literal kind = 0 in the SQL
// text, not a bound parameter.
private const val SESSION_CANDIDATES_SQL =
    "SELECT session_id, SUM(CASE WHEN kind = 0 THEN 1 ELSE 0 END) AS clicks, " +
        "MIN(CASE WHEN kind = 0 THEN ts END) AS first_click_time FROM event " +
        "WHERE app_id = ? GROUP BY session_id " +
        "HAVING MAX(CASE WHEN user_id IS NULL THEN 1 ELSE 0 END) = 1"

// Part 2 of the selection rule: the start row of a session, kind = 1
// and user_id IS NULL (the maintainer's decision 2 of 2026-09-26; the
// design states that a start row always has user_id IS NULL). This
// statement runs on the index event_start. A session without a start
// row gives no row here; the left join below then keeps the fallback of
// part 1, the path (unknown) and the time of the first click.
// internal, not private: the plan test of issue #113 runs
// `EXPLAIN QUERY PLAN` over this exact text, the same rule as
// ELEMENT_TOTALS_SQL of octometer.monitor.elements.
internal const val SESSION_STARTS_SQL =
    "SELECT session_id, path, referrer_host, ts AS start_time FROM event " +
        "WHERE app_id = ? AND kind = 1 AND user_id IS NULL"

// The page statement: part 1 (sess) left-joined with part 2 (starts) on
// session_id. The order startTime DESC, sessionId ASC is the fixed
// order of D13; sessionId is the unique column that ends the order, so
// a tie of startTime still gives one total order.
private const val SESSION_PAGE_SQL =
    "SELECT sess.session_id AS session_id, starts.path AS first_path, " +
        "starts.referrer_host AS source, " +
        "COALESCE(starts.start_time, sess.first_click_time) AS start_time, sess.clicks AS clicks " +
        "FROM ($SESSION_CANDIDATES_SQL) AS sess " +
        "LEFT JOIN ($SESSION_STARTS_SQL) AS starts ON starts.session_id = sess.session_id " +
        "ORDER BY start_time DESC, sess.session_id ASC LIMIT ? OFFSET ?"

// The firstPath filter (a bound parameter, issue #113 step 2) keeps only
// a session with a start row of that exact path. A session with no
// start row can never carry a firstPath value, thus the join here is an
// inner join, not a left join.
private const val SESSION_PAGE_FILTERED_SQL =
    "SELECT sess.session_id AS session_id, starts.path AS first_path, " +
        "starts.referrer_host AS source, " +
        "COALESCE(starts.start_time, sess.first_click_time) AS start_time, sess.clicks AS clicks " +
        "FROM ($SESSION_CANDIDATES_SQL) AS sess " +
        "JOIN ($SESSION_STARTS_SQL) AS starts ON starts.session_id = sess.session_id AND starts.path = ? " +
        "ORDER BY start_time DESC, sess.session_id ASC LIMIT ? OFFSET ?"

private const val SESSION_COUNT_SQL = "SELECT COUNT(*) FROM ($SESSION_CANDIDATES_SQL)"

private const val SESSION_COUNT_FILTERED_SQL =
    "SELECT COUNT(*) FROM ($SESSION_CANDIDATES_SQL) AS sess " +
        "JOIN ($SESSION_STARTS_SQL) AS starts ON starts.session_id = sess.session_id AND starts.path = ?"

// The user id of the earliest sign-in of a session (the maintainer's
// correction of 2026-09-26, and the KDoc of SessionRow.userId above).
// This statement runs once for each row of the page, never for the
// whole app, because a page holds a maximum of SESSION_PAGE_SIZE rows.
private const val SESSION_SIGN_IN_SQL =
    "SELECT user_id FROM event WHERE app_id = ? AND session_id = ? AND user_id IS NOT NULL " +
        "ORDER BY ts ASC LIMIT 1"

private const val APP_EXISTS_SQL = "SELECT 1 FROM app WHERE id = ?"

private val TIMESTAMP_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/**
 * Installs `GET /api/apps/{appId}/sessions` on the route tree (issue
 * #113). `anonymous=true` is mandatory in this version; each other
 * value, or a missing value, gives 400. `firstPath` is optional, and it
 * travels as a bound parameter. `page` follows the page rule of issue
 * #112: an empty or a missing value means page 1, and a bad value gives
 * 400.
 */
fun Route.sessionsRoute(database: SqliteDatabase) {
    get("/api/apps/{appId}/sessions") {
        val appId = call.parameters["appId"]?.toLongOrNull()
        if (appId == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody(APP_ID_MESSAGE))
            return@get
        }
        val anonymousValues = call.request.queryParameters.getAll("anonymous") ?: emptyList()
        if (anonymousValues != listOf("true")) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody(ANONYMOUS_MESSAGE))
            return@get
        }
        val firstPath = call.request.queryParameters["firstPath"]?.takeIf { it.isNotEmpty() }
        val requestedPage = parsePage(call.request.queryParameters["page"])
        if (requestedPage == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody(PAGE_MESSAGE))
            return@get
        }
        when (val result = loadAnonymousSessions(database, appId, firstPath, requestedPage)) {
            SessionsResult.AppNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorBody(APP_NOT_FOUND_MESSAGE))
            is SessionsResult.Success ->
                call.respond(SessionsResponse(page = result.page, pageCount = result.pageCount, rows = result.rows))
        }
    }
}

// Copied from the form of octometer.monitor.users.UserTotalsRoute
// (the maintainer's correction of 2026-09-26 forbids a new shared
// helper with the route of issue #112). A missing page or an empty page
// defaults to 1. A present, non-empty page must be a positive whole
// number, else this function returns null and the route gives 400.
private fun parsePage(raw: String?): Int? {
    if (raw.isNullOrEmpty()) return 1
    val page = raw.toIntOrNull() ?: return null
    return page.takeIf { it >= 1 }
}

private fun ceilDiv(total: Int, size: Int): Int = (total + size - 1) / size

private fun appExists(reader: Connection, appId: Long): Boolean =
    reader.prepareStatement(APP_EXISTS_SQL).use { statement ->
        statement.setLong(1, appId)
        statement.executeQuery().use { it.next() }
    }

/**
 * Reads the level of one app: the exists check, the count statement,
 * the page statement, and the sign-in lookup of each row of the page,
 * all inside one `database.read { }` block (the maintainer's correction
 * of 2026-09-26). One read transaction gives every statement here the
 * same snapshot of the store, the same rule that pull request #127 gave
 * `GET /api/apps/{appId}/users`.
 */
internal suspend fun loadAnonymousSessions(
    database: SqliteDatabase,
    appId: Long,
    firstPath: String?,
    requestedPage: Int,
): SessionsResult =
    database.read { reader ->
        if (!appExists(reader, appId)) {
            return@read SessionsResult.AppNotFound
        }
        val total = countSessions(reader, appId, firstPath)
        val pageCount = maxOf(1, ceilDiv(total, SESSION_PAGE_SIZE))
        val page = requestedPage.coerceAtMost(pageCount)
        val candidates = readSessionsPage(reader, appId, firstPath, page)
        val rows = candidates.map { candidate ->
            candidate.toSessionRow(lookupSignInUserId(reader, appId, candidate.sessionId))
        }
        SessionsResult.Success(page = page, pageCount = pageCount, rows = rows)
    }

// internal, not private: a test of a bound parameter reuses this
// function directly, the same rule as octometer.monitor.users.countUserGroups.
internal fun countSessions(reader: Connection, appId: Long, firstPath: String?): Int {
    val sql = if (firstPath == null) SESSION_COUNT_SQL else SESSION_COUNT_FILTERED_SQL
    return reader.prepareStatement(sql).use { statement ->
        var index = 1
        statement.setLong(index++, appId)
        if (firstPath != null) {
            statement.setLong(index++, appId)
            statement.setString(index, firstPath)
        }
        statement.executeQuery().use { result ->
            result.next()
            result.getInt(1)
        }
    }
}

internal data class SessionCandidateRow(
    val sessionId: String,
    val path: String?,
    val referrerHost: String?,
    val startTimeMillis: Long,
    val clicks: Long,
) {
    fun toSessionRow(userId: String?): SessionRow =
        SessionRow(
            sessionId = sessionId,
            firstPath = path ?: UNKNOWN_PATH,
            source = referrerHost ?: DIRECT_SOURCE,
            startTime = TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(startTimeMillis)),
            clicks = clicks,
            userId = userId,
        )
}

internal fun readSessionsPage(
    reader: Connection,
    appId: Long,
    firstPath: String?,
    page: Int,
): List<SessionCandidateRow> {
    val sql = if (firstPath == null) SESSION_PAGE_SQL else SESSION_PAGE_FILTERED_SQL
    return reader.prepareStatement(sql).use { statement ->
        var index = 1
        statement.setLong(index++, appId)
        statement.setLong(index++, appId)
        if (firstPath != null) {
            statement.setString(index++, firstPath)
        }
        statement.setInt(index++, SESSION_PAGE_SIZE)
        statement.setInt(index, (page - 1) * SESSION_PAGE_SIZE)
        statement.executeQuery().use { result ->
            val rows = mutableListOf<SessionCandidateRow>()
            while (result.next()) {
                rows += SessionCandidateRow(
                    sessionId = result.getString("session_id"),
                    path = result.getString("first_path"),
                    referrerHost = result.getString("source"),
                    startTimeMillis = result.getLong("start_time"),
                    clicks = result.getLong("clicks"),
                )
            }
            rows
        }
    }
}

private fun lookupSignInUserId(reader: Connection, appId: Long, sessionId: String): String? =
    reader.prepareStatement(SESSION_SIGN_IN_SQL).use { statement ->
        statement.setLong(1, appId)
        statement.setString(2, sessionId)
        statement.executeQuery().use { result ->
            if (result.next()) result.getString(1) else null
        }
    }
