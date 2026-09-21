package octometer.kit.ktor

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import octometer.kit.core.ingest.IngestException
import octometer.kit.core.ingest.IngestPipeline
import octometer.kit.core.ingest.IngestSettings
import octometer.kit.core.store.EventLogStore
import octometer.kit.core.user.UserIdResolver
import java.time.Clock

/** The default path of the ingest route (design section 4.2, contract rule C12). */
public const val DEFAULT_INGEST_PATH: String = "/api/octometer/v1/clicks"

/** The maximum size of one request body, in bytes (contract rule C18). */
private const val MAX_BODY_BYTES: Long = 16 * 1024

/**
 * Installs the ingest route of design section 4.2 on this [Route].
 *
 * The app decides if this route sits inside `authenticate(optional = true)`.
 * This function adds no authentication check of its own, and it writes no
 * CORS header (design decision D23).
 *
 * The present form of [UserIdResolver] in `kit/jvm-core` takes no
 * [ApplicationCall]. [resolveUserId] reads the user id from the call, and
 * this function adapts it to one [UserIdResolver] for each request.
 *
 * The store call runs inside [Dispatchers.IO] (design decision D23).
 *
 * @param store the event log store of the app.
 * @param ingestPath the path of the route. The default is the path of
 *   contract rule C12.
 * @param settings the settings of design decision D19. The default reads
 *   the process environment.
 * @param clock the clock for `receivedAt` of design section 4.2. The
 *   default is the system clock.
 * @param resolveUserId reads the user id from the current call, or `null`
 *   when no user is signed in (contract rule C6).
 */
public fun Route.octometerIngestRoute(
    store: EventLogStore,
    ingestPath: String = DEFAULT_INGEST_PATH,
    settings: IngestSettings = IngestSettings.fromEnvironment(),
    clock: Clock = Clock.systemUTC(),
    resolveUserId: (ApplicationCall) -> String?,
) {
    post(ingestPath) {
        if (!hasJsonContentType(call)) {
            call.respond(HttpStatusCode.UnsupportedMediaType)
            return@post
        }

        val rawBody = call.receiveLimitedText(MAX_BODY_BYTES)
        if (rawBody == null) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }

        val userIdResolver = UserIdResolver { resolveUserId(call) }
        try {
            withContext(Dispatchers.IO) {
                IngestPipeline.ingest(rawBody, clock, userIdResolver, store, settings)
            }
            call.respond(HttpStatusCode.NoContent)
        } catch (cause: IngestException) {
            call.respond(HttpStatusCode.BadRequest)
        }
    }
}

/**
 * Checks the `Content-Type` header against contract rule C12. The type
 * `application/json` passes with a charset parameter and without one. Each
 * other type, and an absent header, fail this check.
 */
private fun hasJsonContentType(call: ApplicationCall): Boolean {
    val contentType = call.request.contentType()
    return contentType.match(ContentType.Application.Json)
}

/**
 * Reads the request body as text, with a limit of [maxBytes] raw bytes
 * (contract rule C18). This function never reads more than one byte above
 * the limit into memory, so a large body never reaches the heap in full.
 * It returns `null` for a body above the limit.
 */
private suspend fun ApplicationCall.receiveLimitedText(maxBytes: Long): String? {
    val declaredLength = request.header(HttpHeaders.ContentLength)?.toLongOrNull()
    if (declaredLength != null && declaredLength > maxBytes) {
        return null
    }
    @Suppress("DEPRECATION")
    val packet = request.receiveChannel().readRemaining(maxBytes + 1)
    val bytes = packet.readByteArray()
    return if (bytes.size > maxBytes) null else bytes.toString(Charsets.UTF_8)
}
