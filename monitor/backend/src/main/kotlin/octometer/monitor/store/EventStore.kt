package octometer.monitor.store

import java.sql.Connection

/** One event of a page, ready for the insert of step 6. */
data class NewEvent(
    val eventId: String,
    val ts: Long,
    val element: String,
    val sessionId: String,
    val userId: String?,
)

private const val INSERT_EVENT_SQL = """
    INSERT INTO event (app_id, event_id, ts, element, session_id, user_id)
    VALUES (?, ?, ?, ?, ?, ?)
    ON CONFLICT(app_id, event_id) DO NOTHING
"""

private const val UPDATE_CURSOR_SQL = "UPDATE app SET cursor = ? WHERE id = ?"

/**
 * The repository function of step 6 (D4). One call commits one page. It
 * inserts each event, with a duplicate event dropped. It moves the
 * cursor. Both steps sit inside one `BEGIN IMMEDIATE` transaction. A
 * failed step rolls back the whole page, thus the cursor keeps its old
 * value.
 *
 * A cancelled caller still lets the open page finish or roll back on the
 * writer thread. A JDBC call does not stop midway. The exception still
 * reaches the caller.
 */
class EventStore(private val database: SqliteDatabase) {

    suspend fun commitPage(appId: Long, events: List<NewEvent>, cursor: String) {
        database.write { writer ->
            writer.createStatement().use { it.execute("BEGIN IMMEDIATE") }
            var committed = false
            var failure: Throwable? = null
            try {
                insertEvents(writer, appId, events)
                updateCursor(writer, appId, cursor)
                writer.createStatement().use { it.execute("COMMIT") }
                committed = true
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                if (!committed) {
                    rollback(writer, failure)
                }
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
                insert.addBatch()
            }
            insert.executeBatch()
        }
    }

    private fun updateCursor(writer: Connection, appId: Long, cursor: String) {
        writer.prepareStatement(UPDATE_CURSOR_SQL).use { update ->
            update.setString(1, cursor)
            update.setLong(2, appId)
            check(update.executeUpdate() == 1) { "No app row for id $appId." }
        }
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
