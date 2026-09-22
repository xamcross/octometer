package octometer.kit.ktor

import io.ktor.http.BadContentTypeFormatException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import octometer.kit.core.ingest.IngestEvent
import octometer.kit.core.ingest.IngestException
import octometer.kit.core.ingest.IngestPipeline
import octometer.kit.core.ingest.IngestSettings
import octometer.kit.core.store.DeletionResult
import octometer.kit.core.store.EventLogStore
import octometer.kit.core.user.UserIdResolver
import java.time.Clock
import kotlin.coroutines.coroutineContext

/** The default path of the ingest route (design section 4.2, contract rule C12). */
public const val DEFAULT_INGEST_PATH: String = "/api/octometer/v1/clicks"

/** The maximum size of one request body, in bytes (contract rule C18). */
private const val MAX_BODY_BYTES: Long = 16 * 1024

/**
 * The default store dispatcher of [octometerIngestRoute] (design decision
 * D23): a view of [Dispatchers.IO], limited to 8 tasks at the same time.
 * The route never fills the whole shared [Dispatchers.IO] pool of the app
 * this way, also when many requests arrive at the same time. A view of
 * [Dispatchers.IO] is still a part of [Dispatchers.IO], so decision D23
 * stays true.
 */
public fun defaultStoreDispatcher(): CoroutineDispatcher = Dispatchers.IO.limitedParallelism(8)

/**
 * Installs the ingest route of design section 4.2 on this [Route].
 *
 * The app decides if this route sits inside `authenticate(optional = true)`.
 * This function adds no authentication check of its own, and it writes no
 * CORS header (design decision D23). When the app already has a route at
 * [ingestPath], this function never installs its own route there; the
 * route of the app answers instead, and this function stores nothing.
 *
 * [ingestPath] must start with `/`. This function throws
 * [IllegalArgumentException] at once for a path with no leading slash, an
 * empty path included.
 *
 * [resolveUserId] runs on the coroutine of the call, before the store call
 * moves to [storeDispatcher]. Keep it short, and read only the call
 * attributes of the app (for example `call.principal()` or
 * `call.sessions`), because a value that the app keeps in a `ThreadLocal`
 * is lost once the store call moves to a different thread.
 *
 * The store call runs inside [storeDispatcher] (design decision D23). A
 * throw of [resolveUserId] and a throw of [store] each give status 500,
 * never status 400, also when the exception is an
 * [octometer.kit.core.ingest.IngestException]. Only an invalid request
 * body gives status 400. The route writes no exception text into the
 * answer and into the log; the log holds only the class name of the
 * exception (design decision D15).
 *
 * A [CancellationException] of [resolveUserId] or of [store] gives status
 * 500 too. The body stays empty, and the log line stays the same. A
 * timeout of `withTimeout` inside the store is one example. The route
 * rethrows a [CancellationException] only when the coroutine of the call
 * is no longer active, for example after the engine cancels the call.
 * That check separates a real cancellation of the call from a
 * [CancellationException] that the app throws by itself.
 *
 * @param store the event log store of the app.
 * @param ingestPath the path of the route. The default is the path of
 *   contract rule C12. The app must set a read timeout on its engine (for
 *   example `requestReadTimeoutSeconds` of the Netty engine), because this
 *   function sets none for a slow request body.
 * @param settings the settings of design decision D19. The default reads
 *   the process environment.
 * @param clock the clock for `receivedAt` of design section 4.2. The
 *   default is the system clock.
 * @param storeDispatcher the dispatcher of the store call. The default is
 *   [defaultStoreDispatcher].
 * @param resolveUserId reads the user id from the current call, or `null`
 *   when no user is signed in (contract rule C6).
 */
public fun Route.octometerIngestRoute(
    store: EventLogStore,
    ingestPath: String = DEFAULT_INGEST_PATH,
    settings: IngestSettings = IngestSettings.fromEnvironment(),
    clock: Clock = Clock.systemUTC(),
    storeDispatcher: CoroutineDispatcher = defaultStoreDispatcher(),
    resolveUserId: (ApplicationCall) -> String?,
) {
    require(ingestPath.startsWith("/")) {
        "ingestPath must start with a leading slash, but it was \"$ingestPath\"."
    }

    post(ingestPath) {
        try {
            if (!hasJsonContentType(call)) {
                call.respond(HttpStatusCode.UnsupportedMediaType)
                return@post
            }

            val rawBody = call.receiveLimitedText(MAX_BODY_BYTES)
            if (rawBody == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }

            // Rule of the app documentation above: resolveUserId runs here,
            // on the coroutine of the call, before the store call moves to
            // storeDispatcher.
            val userId = resolveUserId(call)
            val userIdResolver = UserIdResolver { userId }
            val defectSafeStore = DefectSafeEventLogStore(store)

            try {
                withContext(storeDispatcher) {
                    IngestPipeline.ingest(rawBody, clock, userIdResolver, defectSafeStore, settings)
                }
            } catch (cause: IngestException) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }

            call.respond(HttpStatusCode.NoContent)
        } catch (cause: CancellationException) {
            if (isRealCancellationOfTheCall()) {
                // The engine cancels the call, for example after the
                // client closes the connection. Netty 3.6.0 does not
                // cancel the call this way today (confirmed by the third
                // security review of this pull request), but a future
                // engine, or a different engine, can cancel it this way.
                // There is nobody to answer. This rethrow must not
                // become a 500 answer.
                throw cause
            }
            // The store or resolveUserId throws a CancellationException
            // of its own, for example from a withTimeout inside the
            // store. The call coroutine is still active, so this branch
            // treats the exception as a defect of the app, not as a
            // real cancellation.
            respondWithDefect(call, cause)
        } catch (cause: Throwable) {
            respondWithDefect(call, cause)
        }
    }
}

/**
 * True when a caught [CancellationException] means a real cancellation of
 * the coroutine of the current call, for example after the engine
 * cancels the call. False when the coroutine of the call is still
 * active. The [CancellationException] then comes from the store or from
 * `resolveUserId` itself. It is not a real cancellation.
 *
 * This function is `internal`, so a test of `kit/jvm-ktor` can check it
 * on its own, against a coroutine that a test cancels itself (see
 * `IngestRouteTest.kt`).
 */
internal suspend fun isRealCancellationOfTheCall(): Boolean = !coroutineContext.isActive

/**
 * Writes the 500 answer and the one log line of design decision D15 for a
 * defect of the app: a log line never holds a user id, a session id, or a
 * connection string. The message of a store exception can hold any of the
 * three, thus the log holds only the class name of the exception.
 */
private suspend fun respondWithDefect(call: ApplicationCall, cause: Throwable) {
    call.application.log.error(
        "The Octometer ingest route failed. The exception class is {}.",
        cause::class.java.name,
    )
    call.respond(HttpStatusCode.InternalServerError)
}

/**
 * Wraps [delegate], so that an [IngestException] of the app never reaches
 * the route as an [IngestException]. Only the parser of `kit/jvm-core` may
 * raise a 400 answer; a store exception always gives 500 (the Javadoc of
 * [EventLogStore.append]).
 *
 * The ingest route calls only [append] on this wrapper. It never calls
 * [deleteByUserId], because issue #35 adds no delete route to this
 * module. The class is `internal`, not `private`, so
 * `DefectSafeEventLogStoreTest` can build it and test the delegate rule
 * of [deleteByUserId] too.
 */
internal class DefectSafeEventLogStore(private val delegate: EventLogStore) : EventLogStore {

    override fun append(events: List<IngestEvent>, userId: String?) {
        try {
            delegate.append(events, userId)
        } catch (cause: IngestException) {
            throw IllegalStateException("The EventLogStore of the app failed.", cause)
        }
    }

    override fun deleteByUserId(userId: String): DeletionResult {
        try {
            return delegate.deleteByUserId(userId)
        } catch (cause: IngestException) {
            throw IllegalStateException("The EventLogStore of the app failed.", cause)
        }
    }
}

/**
 * Checks the `Content-Type` header against contract rule C12. The type
 * `application/json` passes with a charset parameter and without one. Each
 * other type, an absent header, and a malformed header value, fail this
 * check; a malformed value never reaches the answer and never reaches the
 * log.
 */
private fun hasJsonContentType(call: ApplicationCall): Boolean {
    val rawValue = call.request.header(HttpHeaders.ContentType) ?: return false
    val contentType = try {
        ContentType.parse(rawValue)
    } catch (cause: BadContentTypeFormatException) {
        return false
    }
    return contentType.match(ContentType.Application.Json)
}

/**
 * Reads the request body as text, with a limit of [maxBytes] raw bytes
 * (contract rule C18). This function never reads more than one byte above
 * the limit into memory, so a large body never reaches the heap in full.
 * It returns `null` for a body above the limit. It always decodes the body
 * as UTF-8, because RFC 8259 needs UTF-8 for JSON; a `charset` parameter of
 * the `Content-Type` header has no effect. A broken byte sequence gives an
 * invalid character, and the field rules of `kit/jvm-core` then reject it
 * with status 400.
 *
 * `readRemaining(Long)` of `io.ktor.utils.io.ByteReadChannel` is deprecated
 * in Ktor 3.6.0, in favor of `readBuffer(Long)`. This function keeps
 * `readRemaining(Long)`, because `readBuffer(Long)` does not exist in Ktor
 * 3.2.0 (confirmed on 2026-09-21 with `javap` against
 * `ktor-io-jvm-3.2.0.jar` and `ktor-io-jvm-3.6.0.jar`; see README.md for
 * the full note). Ktor is `compileOnly`, thus this function must work
 * against each Ktor 3 version that the kit supports.
 */
private suspend fun ApplicationCall.receiveLimitedText(maxBytes: Long): String? {
    val declaredLength = request.header(HttpHeaders.ContentLength)?.toLongOrNull()
    if (declaredLength != null && declaredLength > maxBytes) {
        return null
    }
    @Suppress("DEPRECATION")
    val bytes = request.receiveChannel().readRemaining(maxBytes + 1).use { it.readByteArray() }
    return if (bytes.size > maxBytes) null else bytes.toString(Charsets.UTF_8)
}
