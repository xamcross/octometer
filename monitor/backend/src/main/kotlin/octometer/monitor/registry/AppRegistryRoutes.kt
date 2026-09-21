package octometer.monitor.registry

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable
private data class ErrorBody(val error: String)

/**
 * The registry routes of steps 3, 4, and 5. Each handler reads the body
 * with [receiveOrNull], so a malformed body never reaches the default
 * error page: that page can quote part of the body, a connection string
 * included (D11, D15).
 */
fun Route.appRegistryRoutes(registry: AppRegistryService) {
    post("/api/apps") {
        val request = receiveOrNull<CreateAppRequest>(call)
        if (request == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody("The body is not a valid app request."))
            return@post
        }
        when (val result = registry.createApp(request)) {
            is CreateAppResult.Created -> call.respond(HttpStatusCode.Created, result.summary)
            is CreateAppResult.InvalidRequest -> call.respond(HttpStatusCode.BadRequest, ErrorBody(result.message))
            is CreateAppResult.NameTaken ->
                call.respond(HttpStatusCode.Conflict, ErrorBody("The name is already registered."))
        }
    }

    patch("/api/apps/{id}") {
        val appId = call.parameters["id"]?.toLongOrNull()
        if (appId == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody("The app id must be a whole number."))
            return@patch
        }
        val request = receiveOrNull<UpdateAppRequest>(call)
        if (request == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody("The body is not a valid app request."))
            return@patch
        }
        when (val result = registry.updateApp(appId, request)) {
            UpdateAppResult.Updated -> call.respond(HttpStatusCode.NoContent)
            UpdateAppResult.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorBody("The app is not registered."))
            is UpdateAppResult.InvalidRequest -> call.respond(HttpStatusCode.BadRequest, ErrorBody(result.message))
            is UpdateAppResult.NameTaken ->
                call.respond(HttpStatusCode.Conflict, ErrorBody("The name is already registered."))
        }
    }

    delete("/api/apps/{id}") {
        val appId = call.parameters["id"]?.toLongOrNull()
        if (appId == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody("The app id must be a whole number."))
            return@delete
        }
        val found = registry.deleteApp(appId)
        if (found) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorBody("The app is not registered."))
        }
    }
}

private suspend inline fun <reified T : Any> receiveOrNull(call: ApplicationCall): T? =
    try {
        call.receive<T>()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (malformedBody: Exception) {
        null
    }
