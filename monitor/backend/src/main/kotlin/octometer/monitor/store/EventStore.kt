package octometer.monitor.store

import java.sql.Connection

/**
 * One event of a page, ready for the insert of step 6. [path] and
 * [referrerHost] hold the value of the document as it is (design
 * decision D5); the reader adds no rule of its own. [kind] is 1 for
 * the element `octo:session-start`, and 0 for each other element
 * (issue #110, section 6).
 */
data class NewEvent(
    val eventId: String,
    val ts: Long,
    val element: String,
    val sessionId: String,
    val userId: String?,
    val path: String? = null,
    val referrerHost: String? = null,
    val kind: Int = 0,
)

/** One invalid document of a page: its hex `_id`, and a fixed reason (issue #27, D5). */
data class SkippedEvent(val eventId: String, val reason: String)

private const val INSERT_EVENT_SQL = """
    INSERT INTO event (app_id, event_id, ts, element, session_id, user_id, path, referrer_host, kind)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(app_id, event_id) DO NOTHING
"""

private const val INSERT_SKIPPED_EVENT_SQL = """
    INSERT INTO skipped_event (app_id, event_id, reason)
    VALUES (?, ?, ?)
    ON CONFLICT(app_id, event_id) DO NOTHING
"""

// The maintainer's decision of the correction round of pull request
// #208 (issue #187): the guard "AND cursor IS ?" rejects a stale
// write. A PATCH of the connection string can reset the cursor to
// NULL between the read of one page and this commit; the guard then
// changes 0 rows, and commitPage rolls the whole page back.
private const val UPDATE_CURSOR_SQL = "UPDATE app SET cursor = ? WHERE id = ? AND cursor IS ?"

private const val RECORD_CYCLE_SUCCESS_SQL =
    "UPDATE app SET last_poll_at = ?, last_success_at = ?, status = ?, " +
        "consecutive_failures = 0, last_error = NULL WHERE id = ?"

private const val RECORD_FAILURE_SQL =
    "UPDATE app SET status = ?, last_error = ?, consecutive_failures = consecutive_failures + 1, " +
        "last_poll_at = ?, next_poll_at = ? WHERE id = ?"

/**
 * The repository function of step 6 (D4). One call commits one page. It
 * inserts each event, with a duplicate event dropped. It also inserts
 * each skipped document, with its fixed reason (issue #27, D5). It
 * moves the cursor, guarded by [expectedCursor]: the value that the
 * caller last saw in the app row, before this page. All three steps
 * sit inside one `BEGIN IMMEDIATE` transaction. A failed step rolls
 * back the whole page, thus the cursor keeps its old value.
 *
 * The maintainer's decision of the correction round of pull request
 * #208 (issue #187, MAJOR 1): a PATCH of the connection string can
 * reset the cursor between the read of this page and this call. The
 * guard then changes 0 rows. This method rolls the page back, and it
 * gives `false`, with no throw. [MongoAppReader.runCycle] then stops
 * the cycle at once, so the reader reads this page again, of the new
 * source, at the next cycle.
 *
 * A cancelled caller still lets the open page finish or roll back on the
 * writer thread. A JDBC call does not stop midway. The exception still
 * reaches the caller.
 */
class EventStore(private val database: SqliteDatabase) {

    suspend fun commitPage(
        appId: Long,
        events: List<NewEvent>,
        cursor: String,
        expectedCursor: String?,
        skippedEvents: List<SkippedEvent> = emptyList(),
    ): Boolean =
        database.write { writer ->
            writer.createStatement().use { it.execute("BEGIN IMMEDIATE") }
            var committed = false
            var cursorMoved = false
            var failure: Throwable? = null
            try {
                insertEvents(writer, appId, events)
                insertSkippedEvents(writer, appId, skippedEvents)
                cursorMoved = updateCursor(writer, appId, cursor, expectedCursor)
                if (cursorMoved) {
                    writer.createStatement().use { it.execute("COMMIT") }
                    committed = true
                }
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                if (!committed) {
                    rollback(writer, failure)
                }
            }
            cursorMoved
        }

    /**
     * Records a good poll cycle (issue #27, D5, D8). It sets
     * `last_poll_at` and `last_success_at` to [nowMillis], epoch
     * milliseconds UTC, the same unit as `created_at` and `event.ts`.
     * It sets `status` to [status]: `OK`, or `INVALID_DATA` after a
     * skip. It also sets `consecutive_failures` to 0 and `last_error`
     * to `NULL` (issue #28, the maintainer's decision 2): a good cycle
     * clears each trace of an earlier failure streak.
     */
    suspend fun recordCycleSuccess(appId: Long, nowMillis: Long, status: String) {
        database.write { writer ->
            writer.prepareStatement(RECORD_CYCLE_SUCCESS_SQL).use { update ->
                update.setLong(1, nowMillis)
                update.setLong(2, nowMillis)
                update.setString(3, status)
                update.setLong(4, appId)
                check(update.executeUpdate() == 1) { "No app row for id $appId." }
            }
        }
    }

    /**
     * Records a failed poll cycle (issue #28, design decision D8, the
     * maintainer's decision 2). It increments `consecutive_failures`,
     * sets `last_poll_at` to [nowMillis], sets `last_error` to
     * [lastError] (the command error code as text, or the exception
     * class name only, never a message or a connection string), sets
     * `status` to [status] (the caller already applied the
     * failed-cycle-count rule of decision 5 of issue #28), and moves
     * `next_poll_at` to [nextPollAt] (the caller already applied the
     * backoff of decision 3 of issue #28).
     */
    suspend fun recordFailure(appId: Long, status: String?, lastError: String?, nextPollAt: Long, nowMillis: Long) {
        database.write { writer ->
            writer.prepareStatement(RECORD_FAILURE_SQL).use { update ->
                update.setString(1, status)
                update.setString(2, lastError)
                update.setLong(3, nowMillis)
                update.setLong(4, nextPollAt)
                update.setLong(5, appId)
                check(update.executeUpdate() == 1) { "No app row for id $appId." }
            }
        }
    }

    private fun insertEvents(writer: Connection, appId: Long, events: List<NewEvent>) {
        if (events.isEmpty()) return
        writer.prepareStatement(INSERT_EVENT_SQL).use { insert ->
            for (event in events) {
                insert.setLong(1, appId)
                insert.setString(2, event.eventId)
                insert.setLong(3, event.ts)
                insert.setString(4, event.element)
                insert.setString(5, event.sessionId)
                insert.setString(6, event.userId)
                insert.setString(7, event.path)
                insert.setString(8, event.referrerHost)
                insert.setInt(9, event.kind)
                insert.addBatch()
            }
            insert.executeBatch()
        }
    }

    private fun insertSkippedEvents(writer: Connection, appId: Long, skippedEvents: List<SkippedEvent>) {
        if (skippedEvents.isEmpty()) return
        writer.prepareStatement(INSERT_SKIPPED_EVENT_SQL).use { insert ->
            for (skipped in skippedEvents) {
                insert.setLong(1, appId)
                insert.setString(2, skipped.eventId)
                insert.setString(3, skipped.reason)
                insert.addBatch()
            }
            insert.executeBatch()
        }
    }

    /** Gives `true` when the guarded update moved the cursor of one row. */
    private fun updateCursor(writer: Connection, appId: Long, cursor: String, expectedCursor: String?): Boolean =
        writer.prepareStatement(UPDATE_CURSOR_SQL).use { update ->
            update.setString(1, cursor)
            update.setLong(2, appId)
            update.setString(3, expectedCursor)
            update.executeUpdate() == 1
        }

    // MAJOR 4 of correction round 1 (Kotlin backend engineer): a finally
    // does the rollback, and a failed ROLLBACK never stays silent.
    private fun rollback(writer: Connection, failure: Throwable?) {
        try {
            writer.createStatement().use { it.execute("ROLLBACK") }
        } catch (rollbackError: Throwable) {
            failure?.addSuppressed(rollbackError)
        }
    }
}
