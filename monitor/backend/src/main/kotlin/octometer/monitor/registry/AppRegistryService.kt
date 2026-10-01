package octometer.monitor.registry

import com.mongodb.ConnectionString
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
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

// Issue #272: the registry now derives the database name from the path
// of the connection string (D13), instead of a required field. Each
// message below is one fixed sentence, with no part of the checked
// text, the same rule as the messages of ConnectionStringValidator.
private const val MESSAGE_DATABASE_MISMATCH = "The database of the field and of the connection string differ."
private const val MESSAGE_DATABASE_MISSING = "Give the database name in the path of the connection string."
private const val MESSAGE_CONNECTION_STRING_UNREADABLE = "The driver cannot read the connection string."

/**
 * MINOR 3 of the security review. Contract C1 fixes the collection name;
 * a major change of the contract uses the second name here.
 */
private val ALLOWED_COLLECTION_NAMES = setOf("octometer_events", "octometer_events_v2")

@Serializable
data class CreateAppRequest(
    val name: String,
    val connectionString: String,
    /**
     * Issue #272: the registry derives this name from the path of
     * [connectionString] (design decision D13). An absent field, or a
     * blank one, takes the derived name. A present field must equal
     * the derived name, else the create gives a 400. When the
     * connection string has no path, the field alone names the
     * database (an API-only caller; see [resolvedDatabaseName]).
     */
    val database: String? = null,
    /**
     * Issue #244: an absent field takes this default. Contract rule C1
     * fixes this name; the second name of [ALLOWED_COLLECTION_NAMES] is
     * for a major change of the contract.
     */
    val collection: String = "octometer_events",
) {
    // MAJOR 2 of the security review: the generated toString of a data
    // class holds every property. This override hides connectionString
    // from a log line, from a require message, and from a debugger.
    override fun toString(): String =
        "CreateAppRequest(name=$name, database=$database, collection=$collection)"
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

/**
 * The response of `GET /api/apps/{id}` (design decision D48, issue
 * #277; formerly issue #226). It never holds a connection string.
 * [pathPatterns] is the route list that the app published, the reader's
 * cached copy, or `null` before the app's first published list.
 * [pathPatternsPublishedAt] is the UTC ISO 8601 text of the `publishedAt`
 * of that list, or `null` with [pathPatterns]. [kitVersion] is the
 * `kitVersion` of that same document, or `null` with [pathPatterns].
 */
@Serializable
data class AppDetail(
    val appId: Long,
    val name: String,
    val pathPatterns: String?,
    val pathPatternsPublishedAt: String?,
    val kitVersion: String?,
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
 *
 * Design decision D48 (issue #277) ends the typed route pattern list of
 * issue #226: [createApp] and [updateApp] take no `pathPatterns` field
 * any more (`AppRegistryRoutes.kt` gives a fixed 400 for a body with
 * that field). [octometer.monitor.mongo.MongoAppReader] is now the one
 * writer of `app.path_patterns`, `app.path_patterns_published_at`, and
 * `app.kit_version`; [getApp] only reads them back.
 */
class AppRegistryService(
    private val database: SqliteDatabase,
    private val secretStore: SecretStore,
    private val eventDeleteChunkSize: Int = EVENT_DELETE_CHUNK_SIZE,
) {

    suspend fun createApp(accountId: Long, request: CreateAppRequest): CreateAppResult {
        val name = request.name.trim()
        if (name.isEmpty()) return CreateAppResult.InvalidRequest("Give a non-empty name.")
        if (name.length > MAX_SHORT_FIELD_LENGTH) return CreateAppResult.InvalidRequest("Give a shorter name.")
        if (request.collection !in ALLOWED_COLLECTION_NAMES) {
            return CreateAppResult.InvalidRequest("Give the collection octometer_events or octometer_events_v2.")
        }
        if (request.connectionString.length > MAX_CONNECTION_STRING_LENGTH) {
            return CreateAppResult.InvalidRequest("Give a shorter connection string.")
        }

        // Issue #272: the connection-string check runs first, so the
        // derivation below never parses a string that already failed
        // it. MAX_SHORT_FIELD_LENGTH then applies to the resolved
        // name, never to the raw field.
        val check = ConnectionStringValidator.check(request.connectionString)
        if (check is ConnectionStringCheck.Invalid) return CreateAppResult.InvalidRequest(check.message)

        val databaseName = when (val resolution = resolvedDatabaseName(request.connectionString, request.database)) {
            is DatabaseNameResolution.Resolved -> resolution.name
            is DatabaseNameResolution.Invalid -> return CreateAppResult.InvalidRequest(resolution.message)
        }
        if (databaseName.length > MAX_SHORT_FIELD_LENGTH) {
            return CreateAppResult.InvalidRequest("Give a shorter database name.")
        }

        val appId = try {
            database.write { writer ->
                insertApp(writer, accountId, name, databaseName, request.collection)
            }
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
        return CreateAppResult.Created(AppSummary(appId, name, databaseName, request.collection))
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
     *
     * Issue #272, design decision D13: [UpdateAppRequest] carries no
     * `database` field. A connection string of this PATCH derives a
     * fresh database name from its path ([resolvedDatabaseName]); a
     * connection string with no path gives the same 400 as a create
     * with neither a field nor a path. A derived name that differs
     * from the stored one writes `database_name` and always resets the
     * poll state (a different database is a different source, D10 and
     * #187), even when the raw connection string text happens to
     * differ in no other way. A derived name that equals the stored
     * one resets the poll state only when the connection string text
     * itself changed. This closes the gap where a stale `database_name`
     * made the reader open the old database (`MongoAppReader.kt`).
     */
    suspend fun updateApp(accountId: Long, appId: Long, request: UpdateAppRequest): UpdateAppResult {
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

        // Issue #272: UpdateAppRequest carries no database field, so the
        // second argument is always null; the path of the new
        // connection string is the only source. A missing path gives
        // the same 400 as a create with neither a field nor a path.
        val newDatabaseName: String? = if (request.connectionString != null) {
            when (val resolution = resolvedDatabaseName(request.connectionString, null)) {
                is DatabaseNameResolution.Resolved -> resolution.name
                is DatabaseNameResolution.Invalid -> return UpdateAppResult.InvalidRequest(resolution.message)
            }
        } else {
            null
        }

        val found = if (name != null) {
            val updated = try {
                database.write { writer -> updateAppName(writer, accountId, appId, name) }
            } catch (constraintFailure: SQLException) {
                if (isUniqueNameViolation(constraintFailure)) {
                    return UpdateAppResult.NameTaken(name)
                }
                throw constraintFailure
            }
            updated
        } else {
            database.read { reader -> appExists(reader, accountId, appId) }
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
            // Issue #272: a database name that differs from the stored
            // one is a new source, the same as a different host (D10,
            // #187); it writes database_name and always resets. A
            // database name that stays the same resets only when the
            // connection string text itself changed.
            val storedDatabaseName = database.read { reader -> readAppDatabaseName(reader, appId) }
            if (newDatabaseName != storedDatabaseName) {
                database.write { writer ->
                    updateAppDatabaseName(writer, appId, requireNotNull(newDatabaseName))
                    resetPollState(writer, appId)
                }
            } else if (connectionStringChanged(oldConnectionString, request.connectionString)) {
                database.write { writer -> resetPollState(writer, appId) }
            }
        }
        return UpdateAppResult.Updated
    }

    /**
     * The read of `GET /api/apps/{id}` (issue #226, the correction of
     * the brief review of 2026-09-27). It gives `null` for an unknown
     * id; it never reads the secret store, so it never gives a
     * connection string.
     */
    suspend fun getApp(accountId: Long, appId: Long): AppDetail? =
        database.read { reader -> readAppDetail(reader, accountId, appId) }

    /**
     * The delete of step 5, in the order of MAJOR 4 of the security
     * review. It removes the secret first. Then it removes the events in
     * chunks, the skipped_event rows, the gap rows, and the app row.
     * Each step commits on its own.
     *
     * Issue #301: the app must belong to [accountId], else this gives
     * `false` (the route answers 404), the same rule as an unknown app
     * id. The orphan-secret sweep of `MonitorServices.open` still
     * recovers a secret whose app row a former, interrupted delete
     * already removed; this method no longer repeats that recovery on
     * its own, because an app row absent from this account gives no
     * safe way to tell an owned secret apart from a foreign one.
     */
    suspend fun deleteApp(accountId: Long, appId: Long): Boolean {
        val appRowExists = database.read { reader -> appExists(reader, accountId, appId) }
        if (!appRowExists) return false

        secretStore.remove(appId)
        deleteEventsInChunks(appId)
        database.write { writer ->
            deleteSkippedEvents(writer, appId)
            deleteGaps(writer, appId)
        }
        return database.write { writer -> deleteAppRow(writer, accountId, appId) }
    }

    private suspend fun deleteEventsInChunks(appId: Long) {
        while (true) {
            val removed = database.write { writer -> deleteEventChunk(writer, appId, eventDeleteChunkSize) }
            if (removed < eventDeleteChunkSize) break
        }
    }
}

private fun insertApp(
    writer: Connection,
    accountId: Long,
    name: String,
    databaseName: String,
    collectionName: String,
): Long {
    // Design decision D48 (issue #277): path_patterns starts NULL. The
    // registry writes it no more; the reader writes it, once the app
    // publishes its first list.
    //
    // Issue #301, brief review M4: account_id is the session's account.
    // The unique rule of 006_accounts.sql is UNIQUE(account_id, name),
    // so a duplicate name of a different account never gives NameTaken.
    writer.prepareStatement(
        "INSERT INTO app (name, database_name, collection_name, created_at, account_id) VALUES (?, ?, ?, ?, ?)",
    ).use { insert ->
        insert.setString(1, name)
        insert.setString(2, databaseName)
        insert.setString(3, collectionName)
        insert.setLong(4, System.currentTimeMillis())
        insert.setLong(5, accountId)
        insert.executeUpdate()
    }
    return writer.createStatement().use { statement ->
        statement.executeQuery("SELECT last_insert_rowid()").use { result ->
            check(result.next()) { "No id after the insert." }
            result.getLong(1)
        }
    }
}

/**
 * Derives the database name from the path of a connection string (issue
 * #272, design decision D13). It reuses the MongoDB driver's own parser
 * ([ConnectionString.getDatabase]), the same class that
 * `defaultMongoClient` of `MongoAppReader.kt` applies to the client
 * settings; the driver already decodes the path and applies the exact
 * grammar of `mongodb://` and `mongodb+srv://`, so this function parses
 * no text on its own. The result is `null` when the connection string
 * has no path.
 *
 * A text that [ConnectionStringValidator.check] already accepts can
 * still fail here, with [IllegalArgumentException] (for example, a bad
 * port or a bad option); the caller catches it.
 */
fun databaseOf(connectionString: String): String? = ConnectionString(connectionString).database

/** The result of [resolvedDatabaseName]. */
private sealed class DatabaseNameResolution {
    data class Resolved(val name: String) : DatabaseNameResolution()
    data class Invalid(val message: String) : DatabaseNameResolution()
}

/**
 * Resolves the database name of a create request, or of a connection
 * string PATCH (issue #272, design decision D13). The path of
 * [connectionString] is the source of truth. [requestDatabase] is the
 * legacy field of [CreateAppRequest]; it is always `null` for a PATCH,
 * because [UpdateAppRequest] carries no such field.
 *
 * The cases: an absent or a blank [requestDatabase] with a path takes
 * the path; a present [requestDatabase] with no path keeps the field
 * (an API-only caller, with no path to check the field against — no
 * end-to-end test drives this case); a present field that equals the
 * path passes; a present field that differs from the path gives
 * [MESSAGE_DATABASE_MISMATCH]; neither a field nor a path gives
 * [MESSAGE_DATABASE_MISSING].
 */
private fun resolvedDatabaseName(connectionString: String, requestDatabase: String?): DatabaseNameResolution {
    val derived = try {
        databaseOf(connectionString)
    } catch (malformed: IllegalArgumentException) {
        return DatabaseNameResolution.Invalid(MESSAGE_CONNECTION_STRING_UNREADABLE)
    }
    val field = requestDatabase?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        field == null && derived != null -> DatabaseNameResolution.Resolved(derived)
        field == null -> DatabaseNameResolution.Invalid(MESSAGE_DATABASE_MISSING)
        derived == null -> DatabaseNameResolution.Resolved(field)
        field == derived -> DatabaseNameResolution.Resolved(derived)
        else -> DatabaseNameResolution.Invalid(MESSAGE_DATABASE_MISMATCH)
    }
}

private fun updateAppName(writer: Connection, accountId: Long, appId: Long, name: String): Boolean =
    writer.prepareStatement("UPDATE app SET name = ? WHERE id = ? AND account_id = ?").use { update ->
        update.setString(1, name)
        update.setLong(2, appId)
        update.setLong(3, accountId)
        update.executeUpdate() == 1
    }

// Issue #301: an app of another account, or an app with a NULL
// account_id (not yet claimed, or the scaffold's pre-existing rows),
// answers false here, so the caller gives the same 404 as an unknown
// id.
private fun appExists(reader: Connection, accountId: Long, appId: Long): Boolean =
    reader.prepareStatement("SELECT 1 FROM app WHERE id = ? AND account_id = ?").use { select ->
        select.setLong(1, appId)
        select.setLong(2, accountId)
        select.executeQuery().use { it.next() }
    }

// Issue #272: reads the stored database name of one app, so updateApp
// can tell a PATCH to a different database apart from one to the same
// database (design decision D13).
private fun readAppDatabaseName(reader: Connection, appId: Long): String? =
    reader.prepareStatement("SELECT database_name FROM app WHERE id = ?").use { select ->
        select.setLong(1, appId)
        select.executeQuery().use { result ->
            if (result.next()) result.getString("database_name") else null
        }
    }

// Issue #272: writes the derived database name of a connection string
// PATCH. MongoAppReader.kt reads this column at the next poll cycle.
private fun updateAppDatabaseName(writer: Connection, appId: Long, databaseName: String) {
    writer.prepareStatement("UPDATE app SET database_name = ? WHERE id = ?").use { update ->
        update.setString(1, databaseName)
        update.setLong(2, appId)
        update.executeUpdate()
    }
}

// The same UTC ISO 8601 with milliseconds form as AppTotalsRoute.kt and
// ElementsRoute.kt (design decision D48, issue #277).
private val PATH_PATTERNS_PUBLISHED_AT_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

private fun readAppDetail(reader: Connection, accountId: Long, appId: Long): AppDetail? =
    reader.prepareStatement(
        "SELECT name, path_patterns, path_patterns_published_at, kit_version FROM app WHERE id = ? AND account_id = ?",
    ).use { select ->
        select.setLong(1, appId)
        select.setLong(2, accountId)
        select.executeQuery().use { result ->
            if (!result.next()) return@use null
            val publishedAtMillis = result.getLong("path_patterns_published_at").takeUnless { result.wasNull() }
            AppDetail(
                appId = appId,
                name = result.getString("name"),
                pathPatterns = result.getString("path_patterns"),
                pathPatternsPublishedAt = publishedAtMillis?.let {
                    PATH_PATTERNS_PUBLISHED_AT_FORMATTER.format(Instant.ofEpochMilli(it))
                },
                kitVersion = result.getString("kit_version"),
            )
        }
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
//
// Issue #30: privileges_checked_at resets to NULL too. A new
// connection string names a new database user; the check of design
// decision D9 has no evidence yet of that user's privileges, so the
// next cycle must run it again, the same as a brand new app.
// Issue #205, decision 5 of the correction round: a PATCH to a
// different connection string also clears app_path, the set of
// distinct path values of the old source. MongoAppReader.runCycle
// reads app_path fresh at the start of each cycle (decision 4), so no
// call here needs to reach the reader; the next cycle of the new
// source then starts with an empty set.
private fun resetPollState(writer: Connection, appId: Long) {
    writer.prepareStatement(
        "UPDATE app SET cursor = NULL, next_poll_at = NULL, last_poll_at = NULL, " +
            "last_success_at = NULL, status = NULL, last_error = NULL, consecutive_failures = 0, " +
            "privileges_checked_at = NULL WHERE id = ?",
    ).use { update ->
        update.setLong(1, appId)
        update.executeUpdate()
    }
    writer.prepareStatement("DELETE FROM app_path WHERE app_id = ?").use { delete ->
        delete.setLong(1, appId)
        delete.executeUpdate()
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

// MAJOR 3 cleanup path of createApp: appId alone, right after this same
// method inserted that exact row, with no session account in scope.
private fun deleteAppRow(writer: Connection, appId: Long): Boolean =
    writer.prepareStatement("DELETE FROM app WHERE id = ?").use { delete ->
        delete.setLong(1, appId)
        delete.executeUpdate() == 1
    }

// Issue #301: the account-scoped delete of deleteApp.
private fun deleteAppRow(writer: Connection, accountId: Long, appId: Long): Boolean =
    writer.prepareStatement("DELETE FROM app WHERE id = ? AND account_id = ?").use { delete ->
        delete.setLong(1, appId)
        delete.setLong(2, accountId)
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
