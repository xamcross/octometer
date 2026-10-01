package octometer.monitor.apps

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.sql.Connection
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import octometer.monitor.accounts.accountId
import octometer.monitor.store.SqliteDatabase

/**
 * One gap of design decision D16 (issue #62). `from` and `to` are UTC
 * ISO 8601 strings with milliseconds, never null: a gap row always
 * holds both times.
 */
@Serializable
data class GapPeriod(val from: String, val to: String)

/**
 * One row of `GET /api/apps` (D13, level 1 of the design). Each time field
 * is a UTC ISO 8601 string with milliseconds, or null when the app holds
 * no such time yet. `gaps` (D16, issue #62) is `[]` for an app with no gap.
 */
@Serializable
data class AppTotalsRow(
    val appId: Long,
    val name: String,
    val clicks: Long,
    val uniqueUsers: Long,
    val uniqueSessions: Long,
    val status: String,
    val lastSuccessAt: String?,
    val lastError: String?,
    val nextPollAt: String?,
    val gaps: List<GapPeriod>,
)

// Rule M4 of the design change (#109, #18): the literal `kind = 0` keeps
// the partial index of event_agg. A bound parameter does not give the
// same plan on each SQLite build, so the filter stays in the SQL text.
// Visibility: internal, so the test of the query plan can reuse the
// exact text that the route runs.
//
// Issue #237 (the correction of 2026-09-27): one statement replaces
// the two former statements CLICKS_SQL and UNIQUE_USERS_SQL. The inner
// query counts the clicks of each user of each app, on the covering
// index event_agg, one pass. The outer query sums those counts to the
// click total, and it counts each app_id/user_id pair with a real user
// id to the user total. The change lowers the level 1 route from
// 585 ms to about 390 ms at 1 million events (the pull request text
// holds the two measured tables).
internal const val CLICKS_AND_USERS_SQL =
    "SELECT app_id, SUM(c) AS clicks, " +
        "SUM(CASE WHEN user_id IS NULL THEN 0 ELSE 1 END) AS users " +
        "FROM (SELECT app_id, user_id, COUNT(*) AS c FROM event WHERE kind = 0 " +
        "GROUP BY app_id, user_id) GROUP BY app_id"

// No `kind` filter here: a level 1 session counts with or without a
// click (maintainer decision of 2026-09-21, issue #18).
internal const val UNIQUE_SESSIONS_SQL =
    "SELECT app_id, COUNT(DISTINCT session_id) AS total FROM event GROUP BY app_id"

// MINOR 7 of correction round 1: D13 names no row order for level 1.
// Order by id, not by name: id is stable and it needs no collation
// rule, and this issue adds no sort feature. A later issue can add a
// name order or a client-side sort, on its own evidence.
//
// Issue #301: account_id = ? keeps this list to the apps of the
// session's account. An app with a NULL account_id (the scaffold's
// pre-existing rows) never matches a bound account id, thus it stays
// out of every account's list.
private const val APPS_SQL =
    "SELECT id, name, status, last_poll_at, last_success_at, last_error, next_poll_at " +
        "FROM app WHERE account_id = ? ORDER BY id"

// The maintainer's correction of the brief review of 2026-09-27 (issue
// #62): the gap table holds no NULL from_ts or to_ts, so this query
// needs no app join and no LEFT JOIN.
internal const val GAPS_SQL = "SELECT app_id, from_ts, to_ts FROM gap ORDER BY app_id, from_ts"

private val TIMESTAMP_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/** Installs `GET /api/apps` on the route tree. Issue #15 adds the other methods. */
fun Route.appTotals(database: SqliteDatabase) {
    get("/api/apps") {
        call.respond(loadAppTotals(database, call.accountId()))
    }
}

/**
 * Reads the app rows of [accountId] (issue #301), the three level 1
 * statements of section 6, and the gap rows of D16, and merges them.
 * One `read` call holds the read-only connection for the five
 * statements; no connection escapes this function.
 */
suspend fun loadAppTotals(database: SqliteDatabase, accountId: Long): List<AppTotalsRow> =
    database.read { reader ->
        val apps = readApps(reader, accountId)
        val (clicks, uniqueUsers) = readClicksAndUsers(reader)
        val uniqueSessions = readTotals(reader, UNIQUE_SESSIONS_SQL)
        val gaps = readGaps(reader)
        // MINOR 1 of correction round 1: named arguments, in the order of
        // D13, stop a silent swap of the maps at a later edit.
        apps.map { app ->
            app.toRow(
                clicks = clicks,
                uniqueUsers = uniqueUsers,
                uniqueSessions = uniqueSessions,
                gaps = gaps[app.id] ?: emptyList(),
            )
        }
    }

private data class AppRecord(
    val id: Long,
    val name: String,
    val status: String?,
    val lastPollAt: Long?,
    val lastSuccessAt: Long?,
    val lastError: String?,
    val nextPollAt: Long?,
) {
    // D8: the derived status NEVER_POLLED replaces the stored status when
    // the app has no poll cycle yet. MINOR 3 of correction round 1: a
    // poll cycle that ran but wrote no status is a defect of that cycle,
    // not a silent NEVER_POLLED (section 10 names this past defect).
    fun toRow(
        clicks: Map<Long, Long>,
        uniqueUsers: Map<Long, Long>,
        uniqueSessions: Map<Long, Long>,
        gaps: List<GapPeriod>,
    ) = AppTotalsRow(
        appId = id,
        name = name,
        clicks = clicks[id] ?: 0L,
        uniqueUsers = uniqueUsers[id] ?: 0L,
        uniqueSessions = uniqueSessions[id] ?: 0L,
        status = if (lastPollAt == null) "NEVER_POLLED" else status ?: "ERROR",
        lastSuccessAt = formatInstant(lastSuccessAt),
        lastError = lastError,
        nextPollAt = formatInstant(nextPollAt),
        gaps = gaps,
    )
}

private fun readApps(connection: Connection, accountId: Long): List<AppRecord> {
    val apps = mutableListOf<AppRecord>()
    connection.prepareStatement(APPS_SQL).apply { setLong(1, accountId) }.use { statement ->
        statement.executeQuery().use { result ->
            while (result.next()) {
                apps += AppRecord(
                    id = result.getLong("id"),
                    name = result.getString("name"),
                    status = result.getString("status"),
                    lastPollAt = result.getLong("last_poll_at").takeUnless { result.wasNull() },
                    lastSuccessAt = result.getLong("last_success_at").takeUnless { result.wasNull() },
                    lastError = result.getString("last_error"),
                    nextPollAt = result.getLong("next_poll_at").takeUnless { result.wasNull() },
                )
            }
        }
    }
    return apps
}

/** Reads each gap row of D16, grouped by app id, in the order of [GAPS_SQL]. */
private fun readGaps(connection: Connection): Map<Long, List<GapPeriod>> {
    val gaps = mutableMapOf<Long, MutableList<GapPeriod>>()
    connection.createStatement().use { statement ->
        statement.executeQuery(GAPS_SQL).use { result ->
            while (result.next()) {
                val appId = result.getLong("app_id")
                val period = GapPeriod(
                    from = formatInstantRequired(result.getLong("from_ts")),
                    to = formatInstantRequired(result.getLong("to_ts")),
                )
                gaps.getOrPut(appId) { mutableListOf() }.add(period)
            }
        }
    }
    return gaps
}

/**
 * Reads [CLICKS_AND_USERS_SQL] and gives the clicks map, then the
 * unique-users map, one query in place of the two former queries
 * (issue #237).
 */
private fun readClicksAndUsers(connection: Connection): Pair<Map<Long, Long>, Map<Long, Long>> {
    val clicks = mutableMapOf<Long, Long>()
    val users = mutableMapOf<Long, Long>()
    connection.createStatement().use { statement ->
        statement.executeQuery(CLICKS_AND_USERS_SQL).use { result ->
            while (result.next()) {
                val appId = result.getLong("app_id")
                clicks[appId] = result.getLong("clicks")
                users[appId] = result.getLong("users")
            }
        }
    }
    return clicks to users
}

private fun readTotals(connection: Connection, sql: String): Map<Long, Long> {
    val totals = mutableMapOf<Long, Long>()
    connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { result ->
            while (result.next()) {
                totals[result.getLong(1)] = result.getLong(2)
            }
        }
    }
    return totals
}

private fun formatInstant(epochMillis: Long?): String? =
    epochMillis?.let { formatInstantRequired(it) }

/** Formats a gap row's `from_ts` or `to_ts` (D16); a gap row holds no NULL time. */
private fun formatInstantRequired(epochMillis: Long): String =
    TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(epochMillis))
