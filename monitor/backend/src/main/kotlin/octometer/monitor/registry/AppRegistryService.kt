package octometer.monitor.registry

import java.sql.Connection
import java.sql.SQLException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import octometer.monitor.mongo.sha256Hex
import octometer.monitor.store.SqliteDatabase
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException

/** The default chunk size of step 5 (the delete of the events). */
const val EVENT_DELETE_CHUNK_SIZE = 10_000

/** MINOR 2 of the security review: a bound on each text field of a request. */
private const val MAX_CONNECTION_STRING_LENGTH = 2_048
private const val MAX_SHORT_FIELD_LENGTH = 200

/**
 * MINOR 3 of the security review. Contract C1 fixes the collection name;
 * a major change of the contract uses the second name here.
 */
private val ALLOWED_COLLECTION_NAMES = setOf("octometer_events", "octometer_events_v2")

@Serializable
data class CreateAppRequest(
    val name: String,
    val connectionString: String,
    val database: String,
    val collection: String,
) {
    // MAJOR 2 of the security review: the generated toString of a data
    // class holds every property. This override hides connectionString
    // from a log line, from a require message, and from a debugger.
    override fun toString(): String = "CreateAppRequest(name=$name, database=$database, collection=$collection)"
}

@Serializable
data class UpdateAppRequest(
    val name: String? = null,
    val connectionString: String? = null,
) {
    override fun toString(): String =
        "UpdateAppRequest(name=$name, connectionString=${if (connectionString == null) "null" else "<hidden>"})"
}

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

    /** MAJOR 4 (security review) and MAJOR 1 (Ktor review): the secret store gave up. */
    object SecretStoreUnavailable : CreateAppResult()
}

sealed class UpdateAppResult {
    object Updated : UpdateAppResult()
    object NotFound : UpdateAppResult()
    data class InvalidRequest(val message: String) : UpdateAppResult()
    data class NameTaken(val name: String) : UpdateAppResult()
    object SecretStoreUnavailable : UpdateAppResult()
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
        if (name.length > MAX_SHORT_FIELD_LENGTH) return CreateAppResult.InvalidRequest("Give a shorter name.")
        if (request.database.isBlank()) return CreateAppResult.InvalidRequest("Give a non-empty database.")
        if (request.database.length > MAX_SHORT_FIELD_LENGTH) {
            return CreateAppResult.InvalidRequest("Give a shorter database name.")
        }
        if (request.collection !in ALLOWED_COLLECTION_NAMES) {
            return CreateAppResult.InvalidRequest("Give the collection octometer_events or octometer_events_v2.")
        }
        if (request.connectionString.length > MAX_CONNECTION_STRING_LENGTH) {
            return CreateAppResult.InvalidRequest("Give a shorter connection string.")
        }

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

        // MAJOR 3 (both reviews): a failed secret write must not leave an
        // orphan app row. This removes the row again and reports the
        // failure, instead of leaving a row with no connection string.
        //
        // MAJOR 2 of the second security review. kotlinx.coroutines.CancellationException
        // is a type alias of java.util.concurrent.CancellationException. A task inside the
        // secret store can throw that class with no real cancellation of this call. This
        // catches every exception the same way. It runs the cleanup inside
        // withContext(NonCancellable). A real cancellation of this call still removes the
        // app row.
        try {
            secretStore.put(appId, request.connectionString)
        } catch (secretFailure: Exception) {
            withContext(NonCancellable) {
                database.write { writer -> deleteAppRow(writer, appId) }
            }
            if (secretFailure is SecretStoreUnavailableException) {
                return CreateAppResult.SecretStoreUnavailable
            }
            throw secretFailure
        }
        return CreateAppResult.Created(AppSummary(appId, name, request.database, request.collection))
    }

    /**
     * The update of step 4. The field check runs before the existence
     * check. A request with no usable field gives 400, also for an
     * unknown id. A request with both fields writes the name first, then
     * the secret; a failed secret write then leaves the new name in place.
     *
     * Issue #187: a connection string that differs from the old one
     * resets the poll state of the app (`resetPollState`), so the next
     * cycle reads the new source from its oldest event. This method
     * compares the old and the new string by their SHA-256 hex only
     * ([sha256Hex]), never by the plain string, and it writes neither
     * string to a log line. It reads the old string from [secretStore]
     * only here, right before it writes the new one. A failed write of
     * the new string changes no column; the reset runs only after that
     * write succeeds.
     */
    suspend fun updateApp(appId: Long, request: UpdateAppRequest): UpdateAppResult {
        val name = request.name?.trim()
        if (name != null && name.isEmpty()) return UpdateAppResult.InvalidRequest("Give a non-empty name.")
        if (name != null && name.length > MAX_SHORT_FIELD_LENGTH) {
            return UpdateAppResult.InvalidRequest("Give a shorter name.")
        }
        if (name == null && request.connectionString == null) {
            return UpdateAppResult.InvalidRequest("Give a name, a connection string, or both.")
        }
        if (request.connectionString != null && request.connectionString.length > MAX_CONNECTION_STRING_LENGTH) {
            return UpdateAppResult.InvalidRequest("Give a shorter connection string.")
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
            val oldConnectionString = try {
                secretStore.get(appId)
            } catch (secretFailure: SecretStoreUnavailableException) {
                return UpdateAppResult.SecretStoreUnavailable
            }
            try {
                secretStore.put(appId, request.connectionString)
            } catch (secretFailure: SecretStoreUnavailableException) {
                return UpdateAppResult.SecretStoreUnavailable
            }
            if (connectionStringChanged(oldConnectionString, request.connectionString)) {
                database.write { writer -> resetPollState(writer, appId) }
            }
        }
        return UpdateAppResult.Updated
    }

    /**
     * The delete of step 5, in the order of MAJOR 4 of the security
     * review. It removes the secret first. Then it removes the events in
     * chunks, the skipped_event rows, the gap rows, and the app row. A
     * stop between two steps leaves an app row with no secret. The owner
     * can see and correct that state. A stop never leaves a secret with
     * no app row. Each step commits on its own. A second call finishes
     * the remaining steps.
     */
    suspend fun deleteApp(appId: Long): Boolean {
        val appRowExists = database.read { reader -> appExists(reader, appId) }
        val secretExists = secretStore.contains(appId)
        if (!appRowExists && !secretExists) return false

        val secretRemoved = secretStore.remove(appId)
        deleteEventsInChunks(appId)
        database.write { writer ->
            deleteSkippedEvents(writer, appId)
            deleteGaps(writer, appId)
        }
        val appRowRemoved = database.write { writer -> deleteAppRow(writer, appId) }
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

// Issue #187: a missing old string counts as a change, because the
// monitor then has no known source for the cursor. This compares the
// SHA-256 hex of each string only; neither string reaches this line.
private fun connectionStringChanged(oldConnectionString: String?, newConnectionString: String): Boolean =
    oldConnectionString == null || sha256Hex(oldConnectionString) != sha256Hex(newConnectionString)

// Issue #187: a PATCH to a different connection string resets the
// poll state, so the reader of section 4.3 starts the new source at
// its oldest event. consecutive_failures resets to 0, the same as a
// good cycle (RECORD_CYCLE_SUCCESS_SQL of EventStore.kt); every other
// column here resets to NULL. See design decision D10.
private fun resetPollState(writer: Connection, appId: Long) {
    writer.prepareStatement(
        "UPDATE app SET cursor = NULL, next_poll_at = NULL, last_poll_at = NULL, " +
            "last_success_at = NULL, status = NULL, last_error = NULL, consecutive_failures = 0 WHERE id = ?",
    ).use { update ->
        update.setLong(1, appId)
        update.executeUpdate()
    }
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

// MINOR 4 of the security review: the old check matched any message that
// held the word UNIQUE, thus a unique rule on a different column would
// give the same wrong result. This checks the SQLite result code and the
// exact column, so only the name rule of the app table maps to NameTaken.
private fun isUniqueNameViolation(error: SQLException): Boolean =
    error is SQLiteException &&
        error.resultCode == SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE &&
        error.message?.contains("app.name") == true
