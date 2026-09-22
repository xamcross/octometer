package octometer.monitor.erasure

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import kotlinx.serialization.Serializable
import octometer.monitor.ErrorBody
import octometer.monitor.store.SqliteDatabase

/** Contract rule C6: a `userId` string has 1 to 254 characters. */
internal const val USER_ID_MAX_LENGTH = 254

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
 * The handler never logs the user id, and it never puts it into an
 * exception message. The database layer treats it as a bound parameter
 * only (D15, and contract rule C43 for its width).
 */
fun Route.userErasureRoutes(database: SqliteDatabase) {
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
        val found = database.read { reader -> appExists(reader, appId) }
        if (!found) {
            call.respond(HttpStatusCode.NotFound, ErrorBody("The app is not registered."))
            return@delete
        }
        val result = eraseUserEvents(database, appId, userId)
        call.respond(HttpStatusCode.OK, UserErasureResponse(result.total, result.checkpointed))
    }
}
