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
import octometer.monitor.store.SqliteDatabase

/**
 * One row of `GET /api/apps` (D13, level 1 of the design). Each time field
 * is a UTC ISO 8601 string with milliseconds, or null when the app holds
 * no such time yet. D13 also names a tenth field, `gaps` (D16). Issue #62
 * owns that field; this issue (#18) does not add it.
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
)

// Rule M4 of the design change (#109, #18): the literal `kind = 0` keeps
// the partial index of event_agg. A bound parameter does not give the
// same plan on each SQLite build, so the filter stays in the SQL text.
// Visibility: internal, so the test of the query plan can reuse the
// exact text that the route runs.
internal const val CLICKS_SQL =
    "SELECT app_id, COUNT(*) AS total FROM event WHERE kind = 0 GROUP BY app_id"
internal const val UNIQUE_USERS_SQL =
    "SELECT app_id, COUNT(DISTINCT user_id) AS total FROM event WHERE kind = 0 GROUP BY app_id"

// No `kind` filter here: a level 1 session counts with or without a
// click (maintainer decision of 2026-09-21, issue #18).
internal const val UNIQUE_SESSIONS_SQL =
    "SELECT app_id, COUNT(DISTINCT session_id) AS total FROM event GROUP BY app_id"

// MINOR 7 of correction round 1: D13 names no row order for level 1.
// Order by id, not by name: id is stable and it needs no collation
// rule, and this issue adds no sort feature. A later issue can add a
// name order or a client-side sort, on its own evidence.
private const val APPS_SQL =
    "SELECT id, name, status, last_poll_at, last_success_at, last_error, next_poll_at " +
        "FROM app ORDER BY id"

private val TIMESTAMP_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/** Installs `GET /api/apps` on the route tree. Issue #15 adds the other methods. */
fun Route.appTotals(database: SqliteDatabase) {
    get("/api/apps") {
        call.respond(loadAppTotals(database))
    }
}

/**
 * Reads the app rows and the three level 1 statements of section 6, and
 * merges them. One `read` call holds the read-only connection for the
 * four statements; no connection escapes this function.
 */
suspend fun loadAppTotals(database: SqliteDatabase): List<AppTotalsRow> =
    database.read { reader ->
        val apps = readApps(reader)
        val clicks = readTotals(reader, CLICKS_SQL)
        val uniqueUsers = readTotals(reader, UNIQUE_USERS_SQL)
        val uniqueSessions = readTotals(reader, UNIQUE_SESSIONS_SQL)
        // MINOR 1 of correction round 1: named arguments, in the order of
        // D13, stop a silent swap of the three maps at a later edit.
        apps.map { app ->
            app.toRow(clicks = clicks, uniqueUsers = uniqueUsers, uniqueSessions = uniqueSessions)
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
    )
}

private fun readApps(connection: Connection): List<AppRecord> {
    val apps = mutableListOf<AppRecord>()
    connection.createStatement().use { statement ->
        statement.executeQuery(APPS_SQL).use { result ->
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
    epochMillis?.let { TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(it)) }
