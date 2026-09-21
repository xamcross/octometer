package octometer.monitor.store

import java.sql.Connection
import kotlinx.coroutines.withContext

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
 * The repository function of step 6 (D4). One call commits one page: the
 * insert of each event, with a duplicate event dropped, plus the cursor
 * update, inside one `BEGIN IMMEDIATE` transaction. A failed insert rolls
 * back the whole page, thus the cursor stays at its old value.
 */
class EventStore(private val database: SqliteDatabase) {

    suspend fun commitPage(appId: Long, events: List<NewEvent>, cursor: String) {
        withContext(database.dispatcher) {
            val writer = database.writer
            writer.createStatement().use { it.execute("BEGIN IMMEDIATE") }
            try {
                insertEvents(writer, appId, events)
                updateCursor(writer, appId, cursor)
                writer.createStatement().use { it.execute("COMMIT") }
            } catch (error: Exception) {
                rollbackQuietly(writer)
                throw error
            }
        }
    }

    private fun insertEvents(writer: Connection, appId: Long, events: List<NewEvent>) {
        writer.prepareStatement(INSERT_EVENT_SQL).use { insert ->
            for (event in events) {
                insert.setLong(1, appId)
                insert.setString(2, event.eventId)
                insert.setLong(3, event.ts)
                insert.setString(4, event.element)
                insert.setString(5, event.sessionId)
                insert.setString(6, event.userId)
                insert.executeUpdate()
            }
        }
    }

    private fun updateCursor(writer: Connection, appId: Long, cursor: String) {
        writer.prepareStatement(UPDATE_CURSOR_SQL).use { update ->
            update.setString(1, cursor)
            update.setLong(2, appId)
            update.executeUpdate()
        }
    }

    private fun rollbackQuietly(writer: Connection) {
        runCatching { writer.createStatement().use { it.execute("ROLLBACK") } }
    }
}
