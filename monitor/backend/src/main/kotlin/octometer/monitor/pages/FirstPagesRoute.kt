package octometer.monitor.pages

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.sql.Connection
import kotlinx.serialization.Serializable
import octometer.monitor.ErrorBody
import octometer.monitor.store.SqliteDatabase

/** The row count of one page (issue #112, step 1). */
internal const val FIRST_PAGES_PAGE_SIZE = 50

/** The text of an event row with a `NULL` path (issue #112, step 3, D44). */
internal const val UNKNOWN_PATH = "(unknown)"

private const val APP_ID_MESSAGE = "The app id must be a whole number."
private const val PAGE_MESSAGE = "The page must be a positive whole number."
private const val APP_NOT_FOUND_MESSAGE = "The app is not registered."

// Design decision D44 and section 6: the first-pages statement filters on
// the literal kind = 1, so SQLite keeps the plan on the partial index
// event_first_page. Issue #112, step 2, gives this exact statement text,
// with the literal LIMIT 50 and one bound parameter for the app id and
// one for the offset. The statement holds no COALESCE (issue #112, step
// 3); the API layer writes the text "(unknown)" for a NULL path once the
// row leaves the database.
//
// Visibility: internal, not private. The query-plan test of
// FirstPagesRouteTest runs this exact text through EXPLAIN QUERY PLAN.
internal const val FIRST_PAGES_PAGE_SQL =
    "SELECT path, COUNT(DISTINCT session_id) AS sessions FROM event " +
        "WHERE app_id = ? AND kind = 1 GROUP BY path ORDER BY sessions DESC, path ASC LIMIT 50 OFFSET ?"

// The count statement of the same rows, for pageCount. It shares the one
// filter kind = 1 of the page statement, with app_id as its only bound
// parameter.
internal const val FIRST_PAGES_COUNT_SQL =
    "SELECT COUNT(*) FROM (SELECT path FROM event WHERE app_id = ? AND kind = 1 GROUP BY path)"

private const val APP_EXISTS_SQL = "SELECT 1 FROM app WHERE id = ?"

/** One row of `GET /api/apps/{appId}/first-pages` (D13, D44). */
@Serializable
data class FirstPageRow(val path: String, val sessions: Long)

/** The page shape of D13: the current page, the last page, and the rows. */
@Serializable
data class FirstPagesResponse(val page: Int, val pageCount: Int, val rows: List<FirstPageRow>)

sealed interface FirstPagesResult {
    data class Success(val page: Int, val pageCount: Int, val rows: List<FirstPageRow>) : FirstPagesResult
    data object AppNotFound : FirstPagesResult
}

/**
 * Installs `GET /api/apps/{appId}/first-pages?page=` on the route tree
 * (issue #112). An empty or a missing `page` means page 1, the same rule
 * as the level 2 route. A page above the last page clamps to the last
 * page. A `page` below 1 or a `page` that is not a whole number gives
 * 400.
 */
fun Route.firstPagesRoute(database: SqliteDatabase) {
    get("/api/apps/{appId}/first-pages") {
        val appId = call.parameters["appId"]?.toLongOrNull()
        if (appId == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody(APP_ID_MESSAGE))
            return@get
        }
        val requestedPage = parsePage(call.request.queryParameters["page"])
        if (requestedPage == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody(PAGE_MESSAGE))
            return@get
        }
        when (val result = loadFirstPages(database, appId, requestedPage)) {
            FirstPagesResult.AppNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorBody(APP_NOT_FOUND_MESSAGE))
            is FirstPagesResult.Success ->
                call.respond(FirstPagesResponse(page = result.page, pageCount = result.pageCount, rows = result.rows))
        }
    }
}

// This copy stays local to this package (correction after the brief
// review of issue #112): the parallel route of issue #113 gets no new
// shared helper from this change. A missing page or an empty page means
// page 1, the same way an empty q of the level 2 route means no filter.
private fun parsePage(raw: String?): Int? {
    if (raw.isNullOrEmpty()) return 1
    val page = raw.toIntOrNull() ?: return null
    return page.takeIf { it >= 1 }
}

/**
 * Reads the first-page rows of one app. The exists check, the count
 * statement, and the page statement run in one `database.read { }`
 * block (correction after the brief review), the same rule as the level
 * 2 route since pull request #127: the two statements then read one
 * snapshot, and a poll-loop write that commits between them moves no
 * row across the page boundary.
 */
suspend fun loadFirstPages(
    database: SqliteDatabase,
    appId: Long,
    requestedPage: Int,
): FirstPagesResult =
    database.read { reader ->
        if (!appExists(reader, appId)) {
            return@read FirstPagesResult.AppNotFound
        }
        val total = countFirstPages(reader, appId)
        val pageCount = maxOf(1, ceilDiv(total, FIRST_PAGES_PAGE_SIZE))
        val page = requestedPage.coerceAtMost(pageCount)
        val rows = readFirstPagesPage(reader, appId, page)
        FirstPagesResult.Success(page = page, pageCount = pageCount, rows = rows)
    }

private fun ceilDiv(total: Int, size: Int): Int = (total + size - 1) / size

private fun appExists(reader: Connection, appId: Long): Boolean =
    reader.prepareStatement(APP_EXISTS_SQL).use { statement ->
        statement.setLong(1, appId)
        statement.executeQuery().use { it.next() }
    }

// internal, not private: the break-and-restore proof of the bound
// parameter and of the literal kind = 1 filter calls this function
// directly.
internal fun countFirstPages(reader: Connection, appId: Long): Int =
    reader.prepareStatement(FIRST_PAGES_COUNT_SQL).use { statement ->
        statement.setLong(1, appId)
        statement.executeQuery().use { result ->
            result.next()
            result.getInt(1)
        }
    }

internal fun readFirstPagesPage(reader: Connection, appId: Long, page: Int): List<FirstPageRow> =
    reader.prepareStatement(FIRST_PAGES_PAGE_SQL).use { statement ->
        statement.setLong(1, appId)
        statement.setInt(2, (page - 1) * FIRST_PAGES_PAGE_SIZE)
        statement.executeQuery().use { result ->
            val rows = mutableListOf<FirstPageRow>()
            while (result.next()) {
                rows += FirstPageRow(
                    path = result.getString(1) ?: UNKNOWN_PATH,
                    sessions = result.getLong(2),
                )
            }
            rows
        }
    }
