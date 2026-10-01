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
import io.ktor.server.routing.put
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import octometer.monitor.ErrorBody
import octometer.monitor.accounts.accountId
import octometer.monitor.collect.AppKeyCache
import octometer.monitor.collect.DailyEventCountStore

private const val SECRET_STORE_UNAVAILABLE_MESSAGE = "The secret store is not available. Try again."

/** Issue #296, M8: a `POST /api/apps` body with both fields, or with neither one. */
private const val BOTH_FIELDS_MESSAGE = "Send a connection string or an origin list, not both."
private const val NEITHER_FIELD_MESSAGE = "Send a connection string or an origin list."

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
fun Route.appRegistryRoutes(
    registry: AppRegistryService,
    dailyEventCountStore: DailyEventCountStore,
    appKeyCache: AppKeyCache,
) {
    // Issue #296, M8: the body's own keys pick the path. A body with
    // `connectionString` takes the present, local-developer path
    // (D32); a body with `origins` takes the hosted path of D51, D52,
    // and D57. A body with both, or with neither, gives a fixed 400.
    post("/api/apps") {
        val rawJson = when (val outcome = receiveRawAppJson(call)) {
            is RawAppJsonOutcome.Ok -> outcome.json
            RawAppJsonOutcome.Invalid -> {
                call.respond(HttpStatusCode.BadRequest, ErrorBody("The body is not a valid app request."))
                return@post
            }
            RawAppJsonOutcome.PathPatternsRejected -> {
                call.respond(HttpStatusCode.BadRequest, ErrorBody(PATH_PATTERNS_REJECTED_MESSAGE))
                return@post
            }
        }
        val hasConnectionString = "connectionString" in rawJson
        val hasOrigins = "origins" in rawJson
        when {
            hasConnectionString && hasOrigins -> {
                call.respond(HttpStatusCode.BadRequest, ErrorBody(BOTH_FIELDS_MESSAGE))
                return@post
            }
            hasConnectionString -> createAppWithConnectionString(call, registry, rawJson)
            hasOrigins -> createAppWithOrigins(call, registry, rawJson)
            else -> call.respond(HttpStatusCode.BadRequest, ErrorBody(NEITHER_FIELD_MESSAGE))
        }
    }

    // Issue #296, D52: replaces the origin list of one app. The app of
    // another account, or an unknown id, gives the same 404 as the
    // other routes of this file.
    put("/api/apps/{id}/origins") {
        val appId = call.parameters["id"]?.toLongOrNull()
        if (appId == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody("The app id must be a whole number."))
            return@put
        }
        val rawJson = when (val outcome = receiveRawAppJson(call)) {
            is RawAppJsonOutcome.Ok -> outcome.json
            RawAppJsonOutcome.Invalid -> {
                call.respond(HttpStatusCode.BadRequest, ErrorBody("The body is not a valid app request."))
                return@put
            }
            RawAppJsonOutcome.PathPatternsRejected -> {
                call.respond(HttpStatusCode.BadRequest, ErrorBody(PATH_PATTERNS_REJECTED_MESSAGE))
                return@put
            }
        }
        val request = try {
            Json.decodeFromJsonElement<UpdateOriginsRequest>(rawJson)
        } catch (malformed: SerializationException) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody("The body is not a valid app request."))
            return@put
        } catch (malformed: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody("The body is not a valid app request."))
            return@put
        }
        when (val result = registry.updateOrigins(call.accountId(), appId, request.origins)) {
            is UpdateOriginsResult.Updated -> call.respond(HttpStatusCode.OK, result.detail)
            UpdateOriginsResult.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorBody("The app is not registered."))
            is UpdateOriginsResult.InvalidRequest -> call.respond(HttpStatusCode.BadRequest, ErrorBody(result.message))
        }
    }

    // Issue #299, D5, D40: replaces the typed route pattern list of one
    // app, the same model as PUT /api/apps/{id}/origins above. The app
    // of another account, or an unknown id, gives the same 404 as the
    // other routes of this file.
    put("/api/apps/{id}/routes") {
        val appId = call.parameters["id"]?.toLongOrNull()
        if (appId == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody("The app id must be a whole number."))
            return@put
        }
        val rawJson = when (val outcome = receiveRawAppJson(call)) {
            is RawAppJsonOutcome.Ok -> outcome.json
            RawAppJsonOutcome.Invalid -> {
                call.respond(HttpStatusCode.BadRequest, ErrorBody("The body is not a valid app request."))
                return@put
            }
            RawAppJsonOutcome.PathPatternsRejected -> {
                call.respond(HttpStatusCode.BadRequest, ErrorBody(PATH_PATTERNS_REJECTED_MESSAGE))
                return@put
            }
        }
        val request = try {
            Json.decodeFromJsonElement<UpdateRoutesRequest>(rawJson)
        } catch (malformed: SerializationException) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody("The body is not a valid app request."))
            return@put
        } catch (malformed: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody("The body is not a valid app request."))
            return@put
        }
        when (val result = registry.updateRoutes(call.accountId(), appId, request.patterns)) {
            is UpdateRoutesResult.Updated -> {
                // Issue #299, M8: the cache clears before the answer, so
                // a /collect request right after this response always
                // reads the fresh list, with no 10-second wait.
                appKeyCache.clear()
                call.respond(HttpStatusCode.OK, result.detail)
            }
            UpdateRoutesResult.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorBody("The app is not registered."))
            is UpdateRoutesResult.InvalidRequest -> call.respond(HttpStatusCode.BadRequest, ErrorBody(result.message))
        }
    }

    // Issue #296, D51: rotates the secret key of one app. The plain
    // secret key shows in this one answer only.
    post("/api/apps/{id}/secret") {
        val appId = call.parameters["id"]?.toLongOrNull()
        if (appId == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody("The app id must be a whole number."))
            return@post
        }
        when (val result = registry.rotateSecret(call.accountId(), appId)) {
            is RotateSecretResult.Rotated -> call.respond(HttpStatusCode.OK, result.rotation)
            RotateSecretResult.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorBody("The app is not registered."))
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
        val detail = registry.getApp(call.accountId(), appId)
        if (detail == null) {
            call.respond(HttpStatusCode.NotFound, ErrorBody("The app is not registered."))
        } else {
            // Issue #297 (D50, the brief review of 2026-10-01, M5): the
            // collector's own daily drop count, behind the account
            // filter that registry.getApp already applied above.
            val droppedToday = dailyEventCountStore.droppedToday(appId)
            call.respond(HttpStatusCode.OK, detail.copy(droppedToday = droppedToday))
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
        when (val result = registry.updateApp(call.accountId(), appId, request)) {
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
            registry.deleteApp(call.accountId(), appId)
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

/**
 * The result of [receiveRawAppJson]: the raw body of [Ok], so a caller
 * can dispatch on the body's own keys (issue #296, M8), or the one
 * reason the body carries no usable JSON object at all.
 */
private sealed class RawAppJsonOutcome {
    data class Ok(val json: JsonObject) : RawAppJsonOutcome()
    object Invalid : RawAppJsonOutcome()
    object PathPatternsRejected : RawAppJsonOutcome()
}

// Issue #296: the same three catches as receiveAppRequest, with no
// decode of a typed request: the caller of this function picks the
// type from the body's own keys, after this function gives the raw
// JsonObject back.
private suspend fun receiveRawAppJson(call: ApplicationCall): RawAppJsonOutcome {
    val rawJson = try {
        call.receive<JsonObject>()
    } catch (malformedBody: BadRequestException) {
        return RawAppJsonOutcome.Invalid
    } catch (wrongShape: ContentConvertException) {
        return RawAppJsonOutcome.Invalid
    } catch (noUsableBody: ContentTransformationException) {
        return RawAppJsonOutcome.Invalid
    }
    if ("pathPatterns" in rawJson) return RawAppJsonOutcome.PathPatternsRejected
    return RawAppJsonOutcome.Ok(rawJson)
}

/** The legacy, local-developer path of `POST /api/apps` (design decision D32, issue #296). */
private suspend fun createAppWithConnectionString(call: ApplicationCall, registry: AppRegistryService, rawJson: JsonObject) {
    val request = try {
        Json.decodeFromJsonElement<CreateAppRequest>(rawJson)
    } catch (malformed: SerializationException) {
        call.respond(HttpStatusCode.BadRequest, ErrorBody("The body is not a valid app request."))
        return
    } catch (malformed: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, ErrorBody("The body is not a valid app request."))
        return
    }
    when (val result = registry.createApp(call.accountId(), request)) {
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

/** The hosted path of `POST /api/apps` (design decisions D51, D52, D57, issue #296). */
private suspend fun createAppWithOrigins(call: ApplicationCall, registry: AppRegistryService, rawJson: JsonObject) {
    val request = try {
        Json.decodeFromJsonElement<CreateAppWithOriginsRequest>(rawJson)
    } catch (malformed: SerializationException) {
        call.respond(HttpStatusCode.BadRequest, ErrorBody("The body is not a valid app request."))
        return
    } catch (malformed: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, ErrorBody("The body is not a valid app request."))
        return
    }
    when (val result = registry.createAppWithOrigins(call.accountId(), request)) {
        is CreateAppKeysResult.Created -> {
            call.response.header(HttpHeaders.Location, "/api/apps/${result.keys.appId}")
            call.respond(HttpStatusCode.Created, result.keys)
        }
        is CreateAppKeysResult.InvalidRequest -> call.respond(HttpStatusCode.BadRequest, ErrorBody(result.message))
        is CreateAppKeysResult.NameTaken ->
            call.respond(HttpStatusCode.Conflict, ErrorBody("The name is already registered."))
        CreateAppKeysResult.ClusterMissing ->
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorBody(CLUSTER_MISSING_MESSAGE))
    }
}
