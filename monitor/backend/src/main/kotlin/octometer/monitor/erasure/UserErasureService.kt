package octometer.monitor.erasure

import java.sql.Connection
import octometer.monitor.store.SqliteDatabase
import org.slf4j.LoggerFactory

// This file erases the monitor copy of the events only. The app keeps
// its own copy in its MongoDB store. Design decision D15 sets the order
// of the erasure in three steps:
//
// 1. Run the erasure of #35 in the app.
// 2. Wait for one full poll cycle of the monitor (the refresh time of
//    the mode).
// 3. Call this route: DELETE /api/apps/{appId}/events?userId=<id>.
//
// A call before step 2 finishes lets a poll cycle read the erased events
// again from the app store. The monitor row of this user then comes
// back (see monitor/backend/README.md).

private val log = LoggerFactory.getLogger("octometer.monitor.erasure.UserErasureService")

/** The maximum count of a WAL checkpoint try (BLOCKER 1, privacy review of #61). */
internal const val CHECKPOINT_MAX_ATTEMPTS = 5

/** The pause between two checkpoint tries, in milliseconds. */
internal const val CHECKPOINT_RETRY_DELAY_MILLIS = 100L

// The event kind of design section 6: 0 is a click, 1 is a session
// start. Both kinds carry a user_id, so the erasure checks each kind.
// The literal kind value sits in the SQL text, not in a bound
// parameter. A partial index only serves a query whose text repeats
// its own WHERE clause (design rule M4).
//
// Each statement also names its index with INDEXED BY. A later schema
// change that drops an index gives a loud SQL error at prepare time.
// It never gives a silent table scan (SQL review of #61).
//
// The two SELECT statements need INDEXED BY for a second reason.
// DISTINCT session_id gives its result in index order for free on
// event_agg and on event_start. Without INDEXED BY the planner can
// pick event_session, whose scan needs a temp B-tree for the same
// DISTINCT.
internal const val SQL_SESSION_IDS_KIND0 =
    "SELECT DISTINCT session_id FROM event INDEXED BY event_agg WHERE app_id = ? AND user_id = ? AND kind = 0"
internal const val SQL_SESSION_IDS_KIND1 =
    "SELECT DISTINCT session_id FROM event INDEXED BY event_start WHERE app_id = ? AND user_id = ? AND kind = 1"
internal const val SQL_DELETE_USER_KIND0 =
    "DELETE FROM event INDEXED BY event_agg WHERE app_id = ? AND user_id = ? AND kind = 0"
internal const val SQL_DELETE_USER_KIND1 =
    "DELETE FROM event INDEXED BY event_start WHERE app_id = ? AND user_id = ? AND kind = 1"

// MAJOR 3 (privacy review) and MAJOR 2 (SQL review) of #61 found a
// gap. A row of the user with a kind other than 0 or 1 must not
// survive the erasure. The schema holds no CHECK on kind, so a later
// kind value must not depend on the two statements above.
//
// This guard runs after them, on the same index as the anonymous
// delete. The SQL review measured 8 ms for this statement on an app
// with 110 000 rows.
internal const val SQL_DELETE_USER_OTHER_KIND =
    "DELETE FROM event INDEXED BY event_session WHERE app_id = ? AND user_id = ? AND kind NOT IN (0, 1)"

// event_session (app_id, session_id, element, ts, user_id, kind) is not
// partial, thus this statement carries no kind literal. It matches each
// event kind of the anonymous rows of one session.
internal const val SQL_DELETE_ANONYMOUS_OF_SESSION =
    "DELETE FROM event INDEXED BY event_session WHERE app_id = ? AND session_id = ? AND user_id IS NULL"

/**
 * The row counts of one erasure call. [total] is the field `deleted` of
 * the response. [checkpointed] is `true` only when the WAL checkpoint
 * after the commit moved every frame into the main file (BLOCKER 1).
 */
data class UserErasureResult(
    val deletedUserRows: Int,
    val deletedAnonymousRows: Int,
    val checkpointed: Boolean,
) {
    val total: Int get() = deletedUserRows + deletedAnonymousRows
}

/**
 * The erasure function of issue #61, rules E1 and E2. It reads the
 * session list of the user first, inside the same transaction as the
 * delete. A new event of that user then cannot fall between the read
 * and the delete. It deletes each row of the user, and each row with
 * `user_id IS NULL` of a session of that user. A row of a second user
 * in the same session stays (contract rule C43).
 *
 * A `PRAGMA wal_checkpoint(TRUNCATE)` call runs on the writer
 * connection right after the commit, inside the same
 * [SqliteDatabase.write] call. No other write can then run between the
 * commit and the checkpoint. A busy reader can block the checkpoint.
 * The call retries, and [UserErasureResult.checkpointed] states the
 * final result (BLOCKER 1, privacy review of #61).
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
            val checkpointed = checkpointTruncate(writer)
            if (!checkpointed) {
                log.warn("The WAL checkpoint after the erasure of app {} did not complete.", appId)
            }
            UserErasureResult(deletedUserRows, deletedAnonymousRows, checkpointed)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (!committed) {
                rollback(writer, failure)
            }
        }
    }

/**
 * Runs `PRAGMA wal_checkpoint(TRUNCATE)` on [writer], and reads its
 * answer row. The pragma never throws. Its first column is `busy`. A
 * value of 1 means a reader still holds an old snapshot. The old pages
 * then stay in the WAL file (BLOCKER 1, privacy review of #61).
 *
 * The call retries up to [CHECKPOINT_MAX_ATTEMPTS] times, with a pause
 * of [CHECKPOINT_RETRY_DELAY_MILLIS] between two tries. It returns
 * `true` only when one try answers `busy = 0`. The caller must log a
 * warning, with no user data, when the result is `false`.
 */
private fun checkpointTruncate(writer: Connection): Boolean {
    repeat(CHECKPOINT_MAX_ATTEMPTS) { attempt ->
        val busy = writer.createStatement().use { statement ->
            statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)").use { result ->
                check(result.next()) { "PRAGMA wal_checkpoint gave no row." }
                result.getInt(1)
            }
        }
        if (busy == 0) return true
        if (attempt < CHECKPOINT_MAX_ATTEMPTS - 1) {
            Thread.sleep(CHECKPOINT_RETRY_DELAY_MILLIS)
        }
    }
    return false
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
    val deletedOtherKinds = deleteByUser(writer, SQL_DELETE_USER_OTHER_KIND, appId, userId)
    return deletedClicks + deletedSessionStarts + deletedOtherKinds
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
