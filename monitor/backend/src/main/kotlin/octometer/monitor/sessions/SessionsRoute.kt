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

// Part 1 of the candidate sessions (correction round 2 of 2026-09-26,
// BLOCKER 3): the start row of each session, kind = 1 and
// user_id IS NULL.
//
// BLOCKER 3 of round 2: the shipped database holds no sqlite_stat1
// table (no migration runs ANALYZE), and with no statistics, SQLite
// drove the round-1 form of this statement on event_session (app_id=?
// only), a scan of every event row of the app. The measured cost was
// 4.4 s at 50 000 events, against 28 ms for the round-1 count
// statement that this round replaces.
//
// This form is the round-1 form again: one alias, a plain filter
// (app_id = ?, kind = 1, user_id IS NULL), and a correlated NOT EXISTS
// that keeps the earliest row of each session. Embedded as the left
// branch of a plain UNION, the outer UNION needs each branch sorted
// for its merge step, and SQLite was seen to answer that need by
// driving `s` on event_session (app_id=? only, session_id-ordered)
// instead, a scan of every event row of the app. SESSION_CANDIDATES_SQL
// below now joins the two branches with UNION ALL, not UNION: a plain
// concatenation, with no merge step and no sort demand on either
// branch, and `s` now drives on event_start again with no hint needed
// (round 2 correction, third measurement).
//
// A second, worse defect stayed hidden behind the first one: the
// correlated NOT EXISTS below names `session_id`, a column that
// event_start carries only after `ts`, not next to `app_id` and
// `user_id`. With no statistics, SQLite drove `earlier` on event_start
// too, an unbounded scan for each row of `s` (a scan of up to 50 000
// rows here, for each of up to 50 000 rows of `s`), never a search
// bounded to one session. `INDEXED BY event_session` forces `earlier`
// onto the index that leads with app_id then session_id, a search
// bounded to the rows of one session, regardless of statistics. The
// measured cost fell from about 300 s to the number that the pull
// request text of correction round 2 gives, at 50 000 events.
//
// The NOT EXISTS clause keeps the earliest start row of each session,
// by ts, then by rowid, so the choice is always the same row. `rowid`
// is the built-in row id of SQLite; `event` carries no separate id
// column.
//
// internal, not private: the plan test of issue #113 runs
// `EXPLAIN QUERY PLAN` over the statement that the route executes,
// never over this text alone (round 1, BLOCKER 2). The text stays
// internal so a KDoc reference can name it.
internal const val SESSION_STARTS_SQL =
    "SELECT session_id, path, referrer_host, ts AS start_time FROM event AS s " +
        "WHERE app_id = ? AND kind = 1 AND user_id IS NULL AND NOT EXISTS (" +
        "SELECT 1 FROM event AS earlier INDEXED BY event_session WHERE earlier.app_id = s.app_id " +
        "AND earlier.session_id = s.session_id AND earlier.kind = 1 AND earlier.user_id IS NULL " +
        "AND (earlier.ts < s.ts OR (earlier.ts = s.ts AND earlier.rowid < s.rowid))" +
        ")"

// Part 2 of the candidate sessions: a session with no start row, but
// with a minimum of one click of user_id IS NULL (the selection rule
// of D13). event_agg is the intended index: it leads with app_id then
// user_id, the same index that the level 2 and the level 3 routes use
// for a click filter, thus "user_id IS NULL" here is meant as an index
// term, not a residual filter.
//
// MINOR 1 of the round 2 review (2026-09-26): the shipped database
// holds no sqlite_stat1 table (no migration runs ANALYZE), and with no
// statistics, SQLite drives this statement on event_session (app_id=?
// only) instead, the same statement that a click count and a sign-in
// lookup already run on. Round 2 fixes only the start-row branch of
// BLOCKER 3, part 1 above; this branch keeps its round-1 form. The
// NOT EXISTS clause keeps the earliest row of each session, by ts,
// then by rowid, the same tie rule as part 1.
//
// A session with an anonymous click always has its earliest click as
// an anonymous row, because a click turns from anonymous to signed-in
// once, never back. The row that this statement keeps thus gives the
// time of the first click of that session, the fallback value of D44.
//
// `INDEXED BY event_session` on `earlier`, the same fix and the same
// reason as SESSION_STARTS_SQL above: session_id sits after ts in
// event_agg, thus a correlated NOT EXISTS on session_id alone could
// drive `earlier` on an unbounded scan of event_agg with no
// statistics. event_session leads with app_id then session_id, thus
// this search stays bounded to the rows of one session.
internal const val SESSION_ANONYMOUS_CLICKS_SQL =
    "SELECT session_id, ts AS start_time FROM event AS s " +
        "WHERE app_id = ? AND user_id IS NULL AND kind = 0 AND NOT EXISTS (" +
        "SELECT 1 FROM event AS earlier INDEXED BY event_session WHERE earlier.app_id = s.app_id " +
        "AND earlier.session_id = s.session_id AND earlier.user_id IS NULL AND earlier.kind = 0 " +
        "AND (earlier.ts < s.ts OR (earlier.ts = s.ts AND earlier.rowid < s.rowid))" +
        ")"

// The candidate sessions: part 1 (a start row) union part 2 (a session
// with no start row). Each branch runs on its own index; this
// statement reads no row of a signed-in click, thus it never scans
// the whole table. A session of part 2 carries NULL for its path and
// its source; the route resolves that to "(unknown)" and "(direct)"
// (D44: "The API writes (unknown) for a NULL path").
//
// BLOCKER 3 of correction round 2 (2026-09-26): UNION ALL, not UNION.
// Part 1 already gives one row for each of its sessions (the NOT
// EXISTS clause of SESSION_STARTS_SQL keeps the earliest start row
// only), part 2 gives one row for each of its own sessions the same
// way, and the NOT IN clause of part 2 keeps the two branches
// disjoint. A plain UNION thus removed no row here; it only forced
// SQLite to plan each branch for a sorted merge, which pushed the
// plan of part 1 onto event_session (see the KDoc of
// SESSION_STARTS_SQL). UNION ALL asks for no such sort.
//
// internal, not private: the four statements below embed this text,
// and the plan test of issue #113 runs `EXPLAIN QUERY PLAN` over each
// of those four statements (BLOCKER 2 of correction round 1).
internal const val SESSION_CANDIDATES_SQL =
    "SELECT session_id, path, referrer_host, start_time FROM ($SESSION_STARTS_SQL) " +
        "UNION ALL " +
        "SELECT session_id, NULL AS path, NULL AS referrer_host, start_time " +
        "FROM ($SESSION_ANONYMOUS_CLICKS_SQL) AS anon " +
        "WHERE anon.session_id NOT IN (SELECT session_id FROM ($SESSION_STARTS_SQL))"

// The page statement: the candidate sessions, in the fixed order of
// D13. sessionId is the unique column that ends the order, so a tie of
// startTime still gives one total order.
//
// internal, not private: the plan test of issue #113 runs
// `EXPLAIN QUERY PLAN` over this exact text (BLOCKER 2 of correction
// round 1, 2026-09-26); the test copies no SQL text of its own.
internal const val SESSION_PAGE_SQL =
    "SELECT session_id, path, referrer_host, start_time FROM ($SESSION_CANDIDATES_SQL) " +
        "ORDER BY start_time DESC, session_id ASC LIMIT ? OFFSET ?"

// The firstPath filter (a bound parameter, issue #113 step 2) keeps
// only a session with a start row of that exact path. A candidate
// with no start row carries NULL for path, and NULL never equals a
// bound value, thus that candidate never passes this filter.
internal const val SESSION_PAGE_FILTERED_SQL =
    "SELECT session_id, path, referrer_host, start_time FROM ($SESSION_CANDIDATES_SQL) " +
        "WHERE path = ? ORDER BY start_time DESC, session_id ASC LIMIT ? OFFSET ?"

internal const val SESSION_COUNT_SQL = "SELECT COUNT(*) FROM ($SESSION_CANDIDATES_SQL)"

internal const val SESSION_COUNT_FILTERED_SQL =
    "SELECT COUNT(*) FROM ($SESSION_CANDIDATES_SQL) WHERE path = ?"

// The click count of one session (design decision D44: the count of
// every kind = 0 row, also a row after a sign-in). This statement runs
// once for each row of the page, on the index event_session. A page
// holds a maximum of SESSION_PAGE_SIZE rows, thus this never scans the
// whole table.
internal const val SESSION_CLICKS_SQL =
    "SELECT COUNT(*) FROM event WHERE app_id = ? AND session_id = ? AND kind = 0"

// The user id of the earliest sign-in of a session (the maintainer's
// correction of 2026-09-26, and the KDoc of SessionRow.userId above).
// This statement runs once for each row of the page too, on the index
// event_session.
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
            candidate.toSessionRow(
                clicks = countClicks(reader, appId, candidate.sessionId),
                userId = lookupSignInUserId(reader, appId, candidate.sessionId),
            )
        }
        SessionsResult.Success(page = page, pageCount = pageCount, rows = rows)
    }

// The number of appId placeholders of SESSION_CANDIDATES_SQL: the
// start rows once, the anonymous clicks once, and the start rows a
// second time, inside the NOT IN subquery.
internal const val CANDIDATES_APP_ID_COUNT = 3

// internal, not private: a test of a bound parameter reuses this
// function directly, the same rule as octometer.monitor.users.countUserGroups.
internal fun countSessions(reader: Connection, appId: Long, firstPath: String?): Int {
    val sql = if (firstPath == null) SESSION_COUNT_SQL else SESSION_COUNT_FILTERED_SQL
    return reader.prepareStatement(sql).use { statement ->
        var index = 1
        repeat(CANDIDATES_APP_ID_COUNT) { statement.setLong(index++, appId) }
        if (firstPath != null) {
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
) {
    fun toSessionRow(clicks: Long, userId: String?): SessionRow =
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
        repeat(CANDIDATES_APP_ID_COUNT) { statement.setLong(index++, appId) }
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
                    path = result.getString("path"),
                    referrerHost = result.getString("referrer_host"),
                    startTimeMillis = result.getLong("start_time"),
                )
            }
            rows
        }
    }
}

// internal, not private: a test of the query plan reuses this function
// directly (BLOCKER 2 of correction round 1, 2026-09-26): the route
// runs this exact statement, thus the plan test measures the plan of
// this exact statement, never a bare subquery text.
internal fun countClicks(reader: Connection, appId: Long, sessionId: String): Long =
    reader.prepareStatement(SESSION_CLICKS_SQL).use { statement ->
        statement.setLong(1, appId)
        statement.setString(2, sessionId)
        statement.executeQuery().use { result ->
            result.next()
            result.getLong(1)
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
