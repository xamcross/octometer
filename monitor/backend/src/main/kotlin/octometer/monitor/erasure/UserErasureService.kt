package octometer.monitor.erasure

import java.sql.Connection
import octometer.monitor.store.SqliteDatabase

// This file erases the monitor copy of the events only. The app keeps
// its own copy in its MongoDB store. Issue #35 gives the kit
// (EventLogStore.deleteByUserId) the same erasure width as rule E1. Run
// the erasure of #35 in the app first, else a later poll cycle can read
// the erased events again from the app store, and the monitor row of
// this user comes back (see monitor/backend/README.md).

// The event kind of design section 6: 0 is a click, 1 is a session
// start. Both kinds carry a user_id, so the erasure checks each kind.
// The literal kind value sits in the SQL text, not in a bound parameter,
// because a partial index only serves a query whose text repeats its own
// WHERE clause (design rule M4). Each statement also names its index
// with INDEXED BY. A small test table gives SQLite no real statistics,
// and the planner can then pick a different, valid index for a tiny
// table. INDEXED BY makes the choice exact and turns a future schema
// change that removes the index into a loud SQL error, not a silent
// table scan.
internal const val SQL_SESSION_IDS_KIND0 =
    "SELECT DISTINCT session_id FROM event INDEXED BY event_agg WHERE app_id = ? AND user_id = ? AND kind = 0"
internal const val SQL_SESSION_IDS_KIND1 =
    "SELECT DISTINCT session_id FROM event INDEXED BY event_start WHERE app_id = ? AND user_id = ? AND kind = 1"
internal const val SQL_DELETE_USER_KIND0 =
    "DELETE FROM event INDEXED BY event_agg WHERE app_id = ? AND user_id = ? AND kind = 0"
internal const val SQL_DELETE_USER_KIND1 =
    "DELETE FROM event INDEXED BY event_start WHERE app_id = ? AND user_id = ? AND kind = 1"

// event_session (app_id, session_id, element, ts, user_id, kind) is not
// partial, thus this statement carries no kind literal. It matches each
// event kind of the anonymous rows of one session.
internal const val SQL_DELETE_ANONYMOUS_OF_SESSION =
    "DELETE FROM event INDEXED BY event_session WHERE app_id = ? AND session_id = ? AND user_id IS NULL"

/** The row counts of one erasure call. [total] is the field `deleted` of the response. */
data class UserErasureResult(val deletedUserRows: Int, val deletedAnonymousRows: Int) {
    val total: Int get() = deletedUserRows + deletedAnonymousRows
}

/**
 * The erasure function of issue #61, rules E1 and E2. It reads the
 * session list of the user first, inside the same transaction as the
 * delete, so a new event of that user cannot fall between the read and
 * the delete. It then deletes each row of the user, and each row with
 * `user_id IS NULL` of a session of that user. A row of a second user in
 * the same session stays (contract rule C43).
 *
 * A `PRAGMA wal_checkpoint(TRUNCATE)` call runs on the writer connection
 * right after the commit, inside the same [SqliteDatabase.write] call, so
 * no other write can run between the commit and the checkpoint (design
 * decision D15).
 *
 * The answer never quotes [userId]. A caller must keep it out of a log
 * line and out of an exception message too.
 */
suspend fun eraseUserEvents(database: SqliteDatabase, appId: Long, userId: String): UserErasureResult =
    database.write { writer ->
        writer.createStatement().use { it.execute("BEGIN IMMEDIATE") }
        var committed = false
        var failure: Throwable? = null
        try {
            val sessionIds = sessionIdsOfUser(writer, appId, userId)
            val deletedUserRows = deleteUserRows(writer, appId, userId)
            val deletedAnonymousRows = deleteAnonymousRowsOfSessions(writer, appId, sessionIds)
            writer.createStatement().use { it.execute("COMMIT") }
            committed = true
            writer.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
            UserErasureResult(deletedUserRows, deletedAnonymousRows)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (!committed) {
                rollback(writer, failure)
            }
        }
    }

/** `SELECT 1 FROM app WHERE id = ?`, for the 404 check of the route. */
internal fun appExists(reader: Connection, appId: Long): Boolean =
    reader.prepareStatement("SELECT 1 FROM app WHERE id = ?").use { select ->
        select.setLong(1, appId)
        select.executeQuery().use { it.next() }
    }

private fun sessionIdsOfUser(writer: Connection, appId: Long, userId: String): Set<String> {
    val sessionIds = mutableSetOf<String>()
    sessionIds += querySessionIds(writer, SQL_SESSION_IDS_KIND0, appId, userId)
    sessionIds += querySessionIds(writer, SQL_SESSION_IDS_KIND1, appId, userId)
    return sessionIds
}

private fun querySessionIds(writer: Connection, sql: String, appId: Long, userId: String): List<String> =
    writer.prepareStatement(sql).use { select ->
        select.setLong(1, appId)
        select.setString(2, userId)
        select.executeQuery().use { result ->
            val sessionIds = mutableListOf<String>()
            while (result.next()) {
                sessionIds += result.getString(1)
            }
            sessionIds
        }
    }

private fun deleteUserRows(writer: Connection, appId: Long, userId: String): Int {
    val deletedClicks = deleteByUser(writer, SQL_DELETE_USER_KIND0, appId, userId)
    val deletedSessionStarts = deleteByUser(writer, SQL_DELETE_USER_KIND1, appId, userId)
    return deletedClicks + deletedSessionStarts
}

private fun deleteByUser(writer: Connection, sql: String, appId: Long, userId: String): Int =
    writer.prepareStatement(sql).use { delete ->
        delete.setLong(1, appId)
        delete.setString(2, userId)
        delete.executeUpdate()
    }

private fun deleteAnonymousRowsOfSessions(writer: Connection, appId: Long, sessionIds: Set<String>): Int {
    if (sessionIds.isEmpty()) return 0
    var deletedRows = 0
    writer.prepareStatement(SQL_DELETE_ANONYMOUS_OF_SESSION).use { delete ->
        for (sessionId in sessionIds) {
            delete.setLong(1, appId)
            delete.setString(2, sessionId)
            deletedRows += delete.executeUpdate()
        }
    }
    return deletedRows
}

// The same rollback pattern as EventStore.commitPage: a failed ROLLBACK
// never stays silent, and it never replaces the first failure.
private fun rollback(writer: Connection, failure: Throwable?) {
    try {
        writer.createStatement().use { it.execute("ROLLBACK") }
    } catch (rollbackError: Throwable) {
        failure?.addSuppressed(rollbackError)
    }
}
