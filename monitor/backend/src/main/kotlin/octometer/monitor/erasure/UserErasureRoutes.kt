package octometer.monitor.erasure

import com.mongodb.kotlin.client.coroutine.MongoClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import java.sql.Connection
import kotlinx.serialization.Serializable
import octometer.monitor.ErrorBody
import octometer.monitor.accounts.accountId
import octometer.monitor.accounts.constantTimeHashEquals
import octometer.monitor.accounts.sha256Hex
import octometer.monitor.security.ERASURE_SECRET_HEADER
import octometer.monitor.store.SqliteDatabase
import org.slf4j.LoggerFactory

/** Contract rule C6: a `userId` string has 1 to 254 characters. */
internal const val USER_ID_MAX_LENGTH = 254

private const val APP_NOT_REGISTERED_MESSAGE = "The app is not registered."

private val log = LoggerFactory.getLogger("octometer.monitor.erasure.UserErasureRoutes")

/**
 * The answer of the erasure route. It never holds the user id. [deleted]
 * is the row count. [checkpointed] is `true` when the WAL checkpoint
 * completed (BLOCKER 1, privacy review of #61).
 */
@Serializable
data class UserErasureResponse(val deleted: Int, val checkpointed: Boolean)

/**
 * The erasure route of issue #61: `DELETE /api/apps/{appId}/events?userId=<id>`
 * (design D13). An unknown app id gives 404. A missing, a blank, or an
 * over-long `userId` value gives 400. Each other case runs
 * [eraseUserEvents] and gives 200 with the fields `deleted` and
 * `checkpointed`.
 *
 * Issue #300 (design decision D55, the correction round of 2026-10-01):
 * a request with the header [ERASURE_SECRET_HEADER] needs no session.
 * `RequestGuard.kt` and `SessionCheck.kt` already skip the Origin check
 * and the session check for this exact shape; this handler is the one
 * place that decides the header's own result. A `NULL` secret hash (an
 * app with no secret key) and a wrong secret both give 404, the same
 * text as an unknown app, so a probe learns nothing. For an app of the
 * collector path (a non-`NULL` `app_key`), the cluster erasure of
 * [eraseUserEventsFromCluster] runs first, then the cache erasure of
 * [eraseUserEvents]; a failed cluster call never stops the cache step
 * (design decision D15, the same fail-open rule as a collector write
 * failure of `CollectRoute.kt`). For an app of the kit path (a `NULL`
 * `app_key`), the route keeps the cache erasure only: the app owns its
 * own store.
 *
 * [clusterClientFactory] gives the shared Mongo client of
 * `MonitorServices.sharedWriteClient`; a call with no secret header
 * never reads it (the local run and most tests give no working
 * cluster).
 *
 * The handler never logs the user id or the secret key, and it never
 * puts either one into an exception message. The database layer treats
 * the user id as a bound parameter only (D15, and contract rule C43 for
 * its width).
 */
fun Route.userErasureRoutes(database: SqliteDatabase, clusterClientFactory: () -> MongoClient) {
    delete("/api/apps/{appId}/events") {
        val appId = call.parameters["appId"]?.toLongOrNull()
        if (appId == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody("The app id must be a whole number."))
            return@delete
        }
        val userId = call.request.queryParameters["userId"]?.takeIf { it.isNotBlank() }
        if (userId == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody("Give a user id in the query parameter userId."))
            return@delete
        }
        if (userId.length > USER_ID_MAX_LENGTH) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody("The user id must have at most 254 characters."))
            return@delete
        }

        val secretHeader = call.request.header(ERASURE_SECRET_HEADER)
        if (secretHeader != null) {
            val appRow = database.read { reader -> appForSecretErasure(reader, appId) }
            val secretKeyHash = appRow?.secretKeyHash
            if (secretKeyHash == null || !constantTimeHashEquals(sha256Hex(secretHeader), secretKeyHash)) {
                call.respond(HttpStatusCode.NotFound, ErrorBody(APP_NOT_REGISTERED_MESSAGE))
                return@delete
            }
            if (appRow.appKey != null) {
                try {
                    eraseUserEventsFromCluster(clusterClientFactory(), appRow.databaseName, userId)
                } catch (clusterFailure: RuntimeException) {
                    log.warn(
                        "The cluster erasure of design decision D55 failed for app {}. The exception class is {}.",
                        appId,
                        clusterFailure.javaClass.simpleName,
                    )
                }
            }
            val result = eraseUserEvents(database, appId, userId)
            call.respond(HttpStatusCode.OK, UserErasureResponse(result.total, result.checkpointed))
            return@delete
        }

        val found = database.read { reader -> appExists(reader, call.accountId(), appId) }
        if (!found) {
            call.respond(HttpStatusCode.NotFound, ErrorBody(APP_NOT_REGISTERED_MESSAGE))
            return@delete
        }
        val result = eraseUserEvents(database, appId, userId)
        call.respond(HttpStatusCode.OK, UserErasureResponse(result.total, result.checkpointed))
    }
}

/** The app row of the secret path (design decision D55): no account filter, the id alone. */
private data class SecretErasureAppRow(val secretKeyHash: String?, val appKey: String?, val databaseName: String)

// Issue #300, the correction round of 2026-10-01, point 19: the secret
// path reads the app by its id alone, with no account filter. A NULL
// secretKeyHash (an app of the kit path, migration 007) never matches
// any header value, so the caller's 404 covers that case too.
private fun appForSecretErasure(reader: Connection, appId: Long): SecretErasureAppRow? =
    reader.prepareStatement("SELECT secret_key_hash, app_key, database_name FROM app WHERE id = ?").use { select ->
        select.setLong(1, appId)
        select.executeQuery().use { result ->
            if (!result.next()) {
                null
            } else {
                SecretErasureAppRow(
                    result.getString("secret_key_hash"),
                    result.getString("app_key"),
                    result.getString("database_name"),
                )
            }
        }
    }
