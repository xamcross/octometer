package octometer.monitor.registry

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.ContentConvertException
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import octometer.monitor.ErrorBody

private const val SECRET_STORE_UNAVAILABLE_MESSAGE = "The secret store is not available. Try again."

/**
 * The fixed 400 text of design decision D48 (issue #277): the app
 * publishes its own route list now, so a create or an update body with
 * `pathPatterns` is a client mistake, the same as a body with an
 * unknown shape.
 */
private const val PATH_PATTERNS_REJECTED_MESSAGE = "The app publishes its route list; the monitor takes no list."

/**
 * The registry routes of steps 3, 4, and 5. Each handler reads the body
 * with [receiveAppRequest]. A malformed body then never reaches the
 * default error page. That page can quote part of the body. A
 * connection string can sit in that part (D11, D15).
 */
fun Route.appRegistryRoutes(registry: AppRegistryService) {
    post("/api/apps") {
        val request = when (val outcome = receiveAppRequest<CreateAppRequest>(call)) {
            is AppRequestOutcome.Ok -> outcome.request
            AppRequestOutcome.Invalid -> {
                call.respond(HttpStatusCode.BadRequest, ErrorBody("The body is not a valid app request."))
                return@post
            }
            AppRequestOutcome.PathPatternsRejected -> {
                call.respond(HttpStatusCode.BadRequest, ErrorBody(PATH_PATTERNS_REJECTED_MESSAGE))
                return@post
            }
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

    // Issue #226, the correction of the brief review of 2026-09-27: the
    // edit form of the manage view reads the stored route pattern list
    // through this route when it opens. The answer never holds a
    // connection string. The Host guard and the no-store header of
    // RequestGuard already cover every "/api/" path, this one too.
    get("/api/apps/{id}") {
        val appId = call.parameters["id"]?.toLongOrNull()
        if (appId == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody("The app id must be a whole number."))
            return@get
        }
        val detail = registry.getApp(appId)
        if (detail == null) {
            call.respond(HttpStatusCode.NotFound, ErrorBody("The app is not registered."))
        } else {
            call.respond(HttpStatusCode.OK, detail)
        }
    }

    patch("/api/apps/{id}") {
        val appId = call.parameters["id"]?.toLongOrNull()
        if (appId == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody("The app id must be a whole number."))
            return@patch
        }
        val request = when (val outcome = receiveAppRequest<UpdateAppRequest>(call)) {
            is AppRequestOutcome.Ok -> outcome.request
            AppRequestOutcome.Invalid -> {
                call.respond(HttpStatusCode.BadRequest, ErrorBody("The body is not a valid app request."))
                return@patch
            }
            AppRequestOutcome.PathPatternsRejected -> {
                call.respond(HttpStatusCode.BadRequest, ErrorBody(PATH_PATTERNS_REJECTED_MESSAGE))
                return@patch
            }
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
        // MAJOR 6 (second Ktor review): deleteApp reads and writes the
        // secret store before it touches the app row. A broken or a
        // locked secret file must give 503 here too, with no file path.
        val found = try {
            registry.deleteApp(appId)
        } catch (unavailable: SecretStoreUnavailableException) {
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorBody(SECRET_STORE_UNAVAILABLE_MESSAGE))
            return@delete
        }
        if (found) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorBody("The app is not registered."))
        }
    }
}

/**
 * The result of [receiveAppRequest]: the decoded body of [Ok], or the
 * one reason that a create or an update route gives no body at all
 * (design decision D48, issue #277).
 */
private sealed class AppRequestOutcome<out T> {
    data class Ok<T>(val request: T) : AppRequestOutcome<T>()
    object Invalid : AppRequestOutcome<Nothing>()
    object PathPatternsRejected : AppRequestOutcome<Nothing>()
}

// MINOR 8 (Ktor review): the old catch caught every Exception, thus a
// defect of the serializer looked like a bad request too. This catches
// only the exceptions of a malformed body, a wrong content shape, or a
// missing body.
//
// MAJOR 1 (third Ktor review): a request with no body raises
// CannotTransformContentToTypeException. That class extends
// ContentTransformationException, not BadRequestException, thus the
// two catches above missed it, and the catch-all of StatusPages
// answered 500 for a plain client mistake.
//
// Design decision D48 (issue #277): the read now goes through a
// JsonObject first, so this function can tell a body with a
// `pathPatterns` field apart from each other malformed or wrong-shape
// body, with the same three catches as before. [CreateAppRequest] and
// [UpdateAppRequest] carry no `pathPatterns` field any more (the app
// publishes its own list; the registry never reads it through the
// API), so this is the one place that names the field, only to reject
// it with the fixed sentence of [PATH_PATTERNS_REJECTED_MESSAGE].
private suspend inline fun <reified T : Any> receiveAppRequest(call: ApplicationCall): AppRequestOutcome<T> {
    val rawJson = try {
        call.receive<JsonObject>()
    } catch (malformedBody: BadRequestException) {
        return AppRequestOutcome.Invalid
    } catch (wrongShape: ContentConvertException) {
        return AppRequestOutcome.Invalid
    } catch (noUsableBody: ContentTransformationException) {
        return AppRequestOutcome.Invalid
    }
    if ("pathPatterns" in rawJson) return AppRequestOutcome.PathPatternsRejected
    return try {
        AppRequestOutcome.Ok(Json.decodeFromJsonElement<T>(rawJson))
    } catch (malformed: SerializationException) {
        AppRequestOutcome.Invalid
    } catch (malformed: IllegalArgumentException) {
        AppRequestOutcome.Invalid
    }
}
