package octometer.monitor.registry

import java.sql.Connection
import java.sql.SQLException
import kotlinx.serialization.Serializable
import octometer.monitor.store.SqliteDatabase

/** The default chunk size of step 5 (the delete of the events). */
const val EVENT_DELETE_CHUNK_SIZE = 10_000

@Serializable
data class CreateAppRequest(
    val name: String,
    val connectionString: String,
    val database: String,
    val collection: String,
)

@Serializable
data class UpdateAppRequest(
    val name: String? = null,
    val connectionString: String? = null,
)

/** The response of a create and of a list row. It never holds a connection string. */
@Serializable
data class AppSummary(
    val appId: Long,
    val name: String,
    val database: String,
    val collection: String,
)

sealed class CreateAppResult {
    data class Created(val summary: AppSummary) : CreateAppResult()
    data class InvalidRequest(val message: String) : CreateAppResult()
    data class NameTaken(val name: String) : CreateAppResult()
}

sealed class UpdateAppResult {
    object Updated : UpdateAppResult()
    object NotFound : UpdateAppResult()
    data class InvalidRequest(val message: String) : UpdateAppResult()
    data class NameTaken(val name: String) : UpdateAppResult()
}

/**
 * The app registry of steps 2 to 5. It writes each connection string only
 * to [secretStore], never to the SQLite store. [eventDeleteChunkSize] is a
 * test seam for the chunked delete of step 5; production code keeps the
 * default of [EVENT_DELETE_CHUNK_SIZE].
 */
class AppRegistryService(
    private val database: SqliteDatabase,
    private val secretStore: SecretStore,
    private val eventDeleteChunkSize: Int = EVENT_DELETE_CHUNK_SIZE,
) {

    suspend fun createApp(request: CreateAppRequest): CreateAppResult {
        val name = request.name.trim()
        if (name.isEmpty()) return CreateAppResult.InvalidRequest("Give a non-empty name.")
        if (request.database.isBlank()) return CreateAppResult.InvalidRequest("Give a non-empty database.")
        if (request.collection.isBlank()) return CreateAppResult.InvalidRequest("Give a non-empty collection.")

        val check = ConnectionStringValidator.check(request.connectionString)
        if (check is ConnectionStringCheck.Invalid) return CreateAppResult.InvalidRequest(check.message)

        val appId = try {
            database.write { writer -> insertApp(writer, name, request.database, request.collection) }
        } catch (constraintFailure: SQLException) {
            if (isUniqueNameViolation(constraintFailure)) {
                return CreateAppResult.NameTaken(name)
            }
            throw constraintFailure
        }

        secretStore.put(appId, request.connectionString)
        return CreateAppResult.Created(AppSummary(appId, name, request.database, request.collection))
    }

    suspend fun updateApp(appId: Long, request: UpdateAppRequest): UpdateAppResult {
        val name = request.name?.trim()
        if (name != null && name.isEmpty()) return UpdateAppResult.InvalidRequest("Give a non-empty name.")
        if (name == null && request.connectionString == null) {
            return UpdateAppResult.InvalidRequest("Give a name, a connection string, or both.")
        }

        if (request.connectionString != null) {
            val check = ConnectionStringValidator.check(request.connectionString)
            if (check is ConnectionStringCheck.Invalid) return UpdateAppResult.InvalidRequest(check.message)
        }

        val found = if (name != null) {
            val updated = try {
                database.write { writer -> updateAppName(writer, appId, name) }
            } catch (constraintFailure: SQLException) {
                if (isUniqueNameViolation(constraintFailure)) {
                    return UpdateAppResult.NameTaken(name)
                }
                throw constraintFailure
            }
            updated
        } else {
            database.read { reader -> appExists(reader, appId) }
        }
        if (!found) return UpdateAppResult.NotFound

        if (request.connectionString != null) {
            secretStore.put(appId, request.connectionString)
        }
        return UpdateAppResult.Updated
    }

    /**
     * The delete of step 5: the events in chunks, then the skipped_event
     * rows, the gap rows, the app row, and the secret. Each step commits
     * on its own, thus a stopped process leaves a partial result, and a
     * second call finishes the remaining steps, because every step here
     * is safe to repeat.
     */
    suspend fun deleteApp(appId: Long): Boolean {
        deleteEventsInChunks(appId)
        database.write { writer ->
            deleteSkippedEvents(writer, appId)
            deleteGaps(writer, appId)
        }
        val appRowRemoved = database.write { writer -> deleteAppRow(writer, appId) }
        val secretRemoved = secretStore.remove(appId)
        return appRowRemoved || secretRemoved
    }

    private suspend fun deleteEventsInChunks(appId: Long) {
        while (true) {
            val removed = database.write { writer -> deleteEventChunk(writer, appId, eventDeleteChunkSize) }
            if (removed < eventDeleteChunkSize) break
        }
    }
}

private fun insertApp(writer: Connection, name: String, databaseName: String, collectionName: String): Long {
    writer.prepareStatement(
        "INSERT INTO app (name, database_name, collection_name, created_at) VALUES (?, ?, ?, ?)",
    ).use { insert ->
        insert.setString(1, name)
        insert.setString(2, databaseName)
        insert.setString(3, collectionName)
        insert.setLong(4, System.currentTimeMillis())
        insert.executeUpdate()
    }
    return writer.createStatement().use { statement ->
        statement.executeQuery("SELECT last_insert_rowid()").use { result ->
            check(result.next()) { "No id after the insert." }
            result.getLong(1)
        }
    }
}

private fun updateAppName(writer: Connection, appId: Long, name: String): Boolean =
    writer.prepareStatement("UPDATE app SET name = ? WHERE id = ?").use { update ->
        update.setString(1, name)
        update.setLong(2, appId)
        update.executeUpdate() == 1
    }

private fun appExists(reader: Connection, appId: Long): Boolean =
    reader.prepareStatement("SELECT 1 FROM app WHERE id = ?").use { select ->
        select.setLong(1, appId)
        select.executeQuery().use { it.next() }
    }

// A subquery with LIMIT, not the DELETE...LIMIT form: the SQLite build of
// this project does not confirm the update-delete-limit compile option.
private fun deleteEventChunk(writer: Connection, appId: Long, chunkSize: Int): Int =
    writer.prepareStatement(
        "DELETE FROM event WHERE app_id = ? AND rowid IN " +
            "(SELECT rowid FROM event WHERE app_id = ? LIMIT ?)",
    ).use { delete ->
        delete.setLong(1, appId)
        delete.setLong(2, appId)
        delete.setInt(3, chunkSize)
        delete.executeUpdate()
    }

private fun deleteSkippedEvents(writer: Connection, appId: Long) {
    writer.prepareStatement("DELETE FROM skipped_event WHERE app_id = ?").use { delete ->
        delete.setLong(1, appId)
        delete.executeUpdate()
    }
}

private fun deleteGaps(writer: Connection, appId: Long) {
    writer.prepareStatement("DELETE FROM gap WHERE app_id = ?").use { delete ->
        delete.setLong(1, appId)
        delete.executeUpdate()
    }
}

private fun deleteAppRow(writer: Connection, appId: Long): Boolean =
    writer.prepareStatement("DELETE FROM app WHERE id = ?").use { delete ->
        delete.setLong(1, appId)
        delete.executeUpdate() == 1
    }

private fun isUniqueNameViolation(error: SQLException): Boolean =
    error.message?.contains("UNIQUE", ignoreCase = true) == true
