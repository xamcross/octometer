package octometer.monitor.registry

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.ContentConvertException
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import octometer.monitor.ErrorBody

private const val SECRET_STORE_UNAVAILABLE_MESSAGE = "The secret store is not available. Try again."

/**
 * The registry routes of steps 3, 4, and 5. Each handler reads the body
 * with [receiveOrNull]. A malformed body then never reaches the default
 * error page. That page can quote part of the body. A connection string
 * can sit in that part (D11, D15).
 */
fun Route.appRegistryRoutes(registry: AppRegistryService) {
    post("/api/apps") {
        val request = receiveOrNull<CreateAppRequest>(call)
        if (request == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody("The body is not a valid app request."))
            return@post
        }
        when (val result = registry.createApp(request)) {
            is CreateAppResult.Created -> {
                call.response.header(HttpHeaders.Location, "/api/apps/${result.summary.appId}")
                call.respond(HttpStatusCode.Created, result.summary)
            }
            is CreateAppResult.InvalidRequest -> call.respond(HttpStatusCode.BadRequest, ErrorBody(result.message))
            is CreateAppResult.NameTaken ->
                call.respond(HttpStatusCode.Conflict, ErrorBody("The name is already registered."))
            CreateAppResult.SecretStoreUnavailable ->
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorBody(SECRET_STORE_UNAVAILABLE_MESSAGE))
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
            UpdateAppResult.SecretStoreUnavailable ->
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorBody(SECRET_STORE_UNAVAILABLE_MESSAGE))
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

// MINOR 8 (Ktor review): the old catch caught every Exception, thus a
// defect of the serializer looked like a bad request too. This catches
// only the two exceptions of a malformed body or a wrong content shape.
private suspend inline fun <reified T : Any> receiveOrNull(call: ApplicationCall): T? =
    try {
        call.receive<T>()
    } catch (malformedBody: BadRequestException) {
        null
    } catch (wrongShape: ContentConvertException) {
        null
    }
