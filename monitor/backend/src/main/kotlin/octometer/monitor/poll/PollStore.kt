package octometer.monitor.poll

import java.sql.Connection
import octometer.monitor.store.SqliteDatabase

/** One row of the app table, read at one tick (issue #17, decision 5). */
data class AppRow(
    val appId: Long,
    val database: String,
    val collection: String,
    val cursor: String?,
    val nextPollAt: Long?,
)

/**
 * The store seam of the poll scheduler (issue #17, decision 7, MAJOR 7 of
 * the Kotlin review). [PollScheduler] reads the app list and writes each
 * poll result through this interface, never through a concrete
 * [SqliteDatabase] call.
 *
 * [SqlitePollStore] is the production value. A virtual-time test gives an
 * in-memory fake instead, so no scheduler test mixes the virtual clock of
 * `runTest` with the real writer thread of [SqliteDatabase].
 */
interface PollStore {
    suspend fun readApps(): List<AppRow>
    suspend fun writeResult(appId: Long, nextPollAt: Long, cursor: String?)
    suspend fun recordFailure(appId: Long, nextPollAt: Long)
}

/**
 * The production [PollStore]. It runs each read and each write inside one
 * [SqliteDatabase] block, the same SQL text as before the seam of decision
 * 7.
 */
class SqlitePollStore(private val database: SqliteDatabase) : PollStore {

    override suspend fun readApps(): List<AppRow> =
        database.read { reader -> readAppRows(reader) }

    // A deleted app gives zero updated rows here. This never throws for
    // that case (design decision D6, issue #17).
    override suspend fun writeResult(appId: Long, nextPollAt: Long, cursor: String?) {
        database.write { writer -> writeSuccess(writer, appId, nextPollAt, cursor) }
    }

    override suspend fun recordFailure(appId: Long, nextPollAt: Long) {
        database.write { writer -> writeFailure(writer, appId, nextPollAt) }
    }
}

private fun readAppRows(reader: Connection): List<AppRow> =
    reader.createStatement().use { statement ->
        statement.executeQuery(
            "SELECT id, database_name, collection_name, cursor, next_poll_at FROM app",
        ).use { result ->
            val rows = mutableListOf<AppRow>()
            while (result.next()) {
                val nextPollAt = result.getLong("next_poll_at").takeUnless { result.wasNull() }
                rows += AppRow(
                    appId = result.getLong("id"),
                    database = result.getString("database_name"),
                    collection = result.getString("collection_name"),
                    cursor = result.getString("cursor"),
                    nextPollAt = nextPollAt,
                )
            }
            rows
        }
    }

private fun writeSuccess(writer: Connection, appId: Long, nextPollAt: Long, cursor: String?) {
    writer.prepareStatement("UPDATE app SET next_poll_at = ?, cursor = ? WHERE id = ?").use { update ->
        update.setLong(1, nextPollAt)
        update.setString(2, cursor)
        update.setLong(3, appId)
        update.executeUpdate()
    }
}

private fun writeFailure(writer: Connection, appId: Long, nextPollAt: Long) {
    writer.prepareStatement("UPDATE app SET next_poll_at = ? WHERE id = ?").use { update ->
        update.setLong(1, nextPollAt)
        update.setLong(2, appId)
        update.executeUpdate()
    }
}
