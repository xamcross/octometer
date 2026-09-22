package octometer.monitor.erasure

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import kotlinx.serialization.Serializable
import octometer.monitor.ErrorBody
import octometer.monitor.store.SqliteDatabase

/** The answer of the erasure route. It never holds the user id, only the count (D15). */
@Serializable
data class UserErasureResponse(val deleted: Int)

/**
 * The erasure route of issue #61: `DELETE /api/apps/{appId}/events?userId=<id>`
 * (design D13). An unknown app id gives 404. A missing or a blank
 * `userId` value gives 400. Each other case runs [eraseUserEvents] and
 * gives 200 with the field `deleted`.
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
        val found = database.read { reader -> appExists(reader, appId) }
        if (!found) {
            call.respond(HttpStatusCode.NotFound, ErrorBody("The app is not registered."))
            return@delete
        }
        val result = eraseUserEvents(database, appId, userId)
        call.respond(HttpStatusCode.OK, UserErasureResponse(result.total))
    }
}
