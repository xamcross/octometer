package octometer.kit.ktor

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
import octometer.kit.core.ingest.AnonymousDailyCap
import octometer.kit.core.ingest.AnonymousMinuteLimiter
import octometer.kit.core.ingest.IngestRateLimiter
import octometer.kit.core.ingest.IngestRequestProcessor
import octometer.kit.core.ingest.IngestResult
import octometer.kit.core.ingest.IngestSettings
import octometer.kit.core.store.EventLogStore
import java.time.Clock
import kotlin.coroutines.coroutineContext

/** The default path of the ingest route (design section 4.2, contract rule C12). */
public const val DEFAULT_INGEST_PATH: String = "/api/octometer/v1/clicks"

/**
 * The maximum size of one request body, in bytes (contract rule C18). This
 * class uses it only to bound its own streamed read of the body
 * ([receiveLimitedText]); [IngestRequestProcessor] enforces the same limit
 * again against the decoded text (issue #69).
 */
private const val MAX_BODY_BYTES: Long = 16 * 1024

/** Only an ASCII digit sets the trusted proxy count. A Unicode digit does not. */
private val ASCII_DIGITS_PATTERN = Regex("^[0-9]+$")

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
 * **The order of the checks (design decision D43, issue #117; issue #116;
 * issue #69).** [IngestRequestProcessor] of `kit/jvm-core` now holds the
 * order of the checks; this function is a thin adapter over it. Read the
 * Javadoc of [IngestRequestProcessor] for the full order. This function
 * builds one [IngestRequestProcessor.RequestView] for each request, calls
 * [IngestRequestProcessor.beforeBody], reads the body only when that call
 * gives no result of its own, then calls
 * [IngestRequestProcessor.afterBody] inside [storeDispatcher] (design
 * decision D23): the store call of design decision D18, when this request
 * reaches it, then always runs there, at the cost of also running the
 * parse and the two entry counters of [IngestRequestProcessor.afterBody]
 * there for a request that never reaches the store.
 *
 * A throw of [resolveUserId] and a throw of [store] each give status 500,
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
 * @param settings the settings of design decision D19 and design decision
 *   D43. The default reads the process environment.
 * @param clock the clock for `receivedAt` of design section 4.2, and for
 *   [dailyCap]. The default is the system clock.
 * @param storeDispatcher the dispatcher of the store call. The default is
 *   [defaultStoreDispatcher].
 * @param rateLimiter the ingest rate limiter of design decision D20 (issue
 *   #33). The default builds one [IngestRateLimiter] with [clock], held for
 *   the life of this route, so its window state is shared across each
 *   request. The route answers 429 for a rejected request (see the order
 *   above).
 * @param dailyCap the daily anonymous cap of design decision D43 (issue
 *   #117). The default builds one [AnonymousDailyCap] with [clock] and the
 *   two caps of [settings], held for the life of this route.
 * @param minuteLimiter the anonymous per-minute limiter of design decision
 *   D43 (issue #116). The default builds one [AnonymousMinuteLimiter] with
 *   [clock] and the three limits of [settings], held for the life of this
 *   route. It runs only for a request with no user id, when the app
 *   records an anonymous click (see the order above).
 * @param clientIpHeaderName the name of the header that holds the client
 *   address (issue #33, step 3). The default reads
 *   `OCTOMETER_CLIENT_IP_HEADER` once, when this function installs the
 *   route. [IngestRequestProcessor] reads the element that
 *   [trustedProxyCount] counts from the right of that header. A `null`
 *   value, a request with no such header, a value above 64 characters, and
 *   a value with no IPv4 or IPv6 address form, each fall back to the
 *   remote address of the connection. Set this option only behind a proxy
 *   that appends the real client address this way; see
 *   `kit/jvm-ktor/README.md`.
 * @param trustedProxyCount the position, counted from the right of the
 *   header list of [clientIpHeaderName], of the address to trust (design
 *   decision D20, issue #116). The default reads
 *   `OCTOMETER_TRUSTED_PROXY_COUNT` once, at 1 with no such variable. A
 *   count above the length of the header list falls back to the remote
 *   address, the same as a value with no address form. A text value, a
 *   zero, or a negative value stops the app start; the error message
 *   never repeats the raw value.
 *
 *   Set this count to the exact number of trusted proxies in front of
 *   the app, never more. A client already appends its own element to
 *   the header. A count one too high reads that client element
 *   instead. It never reads a proxy's own observed address, so it lets
 *   the client choose its own key. A client can then take the key of
 *   another visitor. It can then exhaust the counters of design
 *   decision D43 of that key.
 * @param resolveUserId reads the user id from the current call, or `null`
 *   when no user is signed in (contract rule C6). This function runs
 *   before the rate limit check, so a rejected request still pays its
 *   cost; keep it short.
 */
public fun Route.octometerIngestRoute(
    store: EventLogStore,
    ingestPath: String = DEFAULT_INGEST_PATH,
    settings: IngestSettings = IngestSettings.fromEnvironment(),
    clock: Clock = Clock.systemUTC(),
    storeDispatcher: CoroutineDispatcher = defaultStoreDispatcher(),
    rateLimiter: IngestRateLimiter = IngestRateLimiter(clock),
    dailyCap: AnonymousDailyCap = AnonymousDailyCap(
        clock,
        settings.anonMaxEventsPerDay(),
        settings.anonEventsPerKeyPerDay(),
    ),
    minuteLimiter: AnonymousMinuteLimiter = AnonymousMinuteLimiter(
        clock,
        settings.anonReqPerMinute(),
        settings.anonEventsPerMinute(),
        settings.anonSessionsPerMinute(),
    ),
    clientIpHeaderName: String? = System.getenv("OCTOMETER_CLIENT_IP_HEADER"),
    trustedProxyCount: Int = trustedProxyCountFromEnvironment(),
    resolveUserId: (ApplicationCall) -> String?,
) {
    require(ingestPath.startsWith("/")) {
        "ingestPath must start with a leading slash, but it was \"$ingestPath\"."
    }
    val processor = IngestRequestProcessor(
        store,
        settings,
        clock,
        rateLimiter,
        minuteLimiter,
        dailyCap,
        clientIpHeaderName,
        trustedProxyCount,
    )

    post(ingestPath) {
        try {
            val view = KtorRequestView(call, resolveUserId)
            val outcome = processor.beforeBody(view)
            if (outcome.mustRespondNow()) {
                respond(call, outcome.result())
                return@post
            }

            val rawBody = call.receiveLimitedText(MAX_BODY_BYTES)
            if (rawBody == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }

            val result = withContext(storeDispatcher) {
                processor.afterBody(outcome, rawBody)
            }
            respond(call, result)
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

/** Writes the answer of [result] on [call]. */
private suspend fun respond(call: ApplicationCall, result: IngestResult) {
    call.respond(HttpStatusCode.fromValue(result.statusCode()))
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
 * The [IngestRequestProcessor.RequestView] of one Ktor call (issue #69).
 * It gives [IngestRequestProcessor] each raw header line, the declared
 * `Content-Length`, the remote address, and [resolveUserId] of the app; it
 * reads no request body.
 */
private class KtorRequestView(
    private val call: ApplicationCall,
    private val resolveUserIdFn: (ApplicationCall) -> String?,
) : IngestRequestProcessor.RequestView {

    override fun headerLines(headerName: String): List<String> =
        call.request.headers.getAll(headerName) ?: emptyList()

    override fun declaredContentLengthBytes(): Long? =
        call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()

    override fun remoteAddress(): String = call.request.local.remoteAddress

    override fun resolveUserId(): String? = resolveUserIdFn(call)
}

/**
 * Reads `OCTOMETER_TRUSTED_PROXY_COUNT` from the process environment
 * (design decision D20, issue #116), or [DEFAULT_TRUSTED_PROXY_COUNT]
 * with no such variable. A text value, a zero, or a negative value stops
 * the app start (see [positiveWholeNumberFromEnvironmentValue]).
 */
private fun trustedProxyCountFromEnvironment(): Int =
    positiveWholeNumberFromEnvironmentValue(
        System.getenv(TRUSTED_PROXY_COUNT_VARIABLE),
        TRUSTED_PROXY_COUNT_VARIABLE,
        DEFAULT_TRUSTED_PROXY_COUNT,
    )

/** The environment variable of the trusted proxy count (design decision D43, issue #116). */
private const val TRUSTED_PROXY_COUNT_VARIABLE = "OCTOMETER_TRUSTED_PROXY_COUNT"

/** The default of [TRUSTED_PROXY_COUNT_VARIABLE] (design decision D43, issue #116). */
private const val DEFAULT_TRUSTED_PROXY_COUNT = 1

/**
 * Turns the raw text of one environment variable into a positive whole
 * number, the form of `IngestSettings.positiveWholeNumberFromValue` of
 * `kit/jvm-core` (design decision D43, issue #116). A `null` [rawValue]
 * gives [defaultValue], with no error.
 *
 * A value of zero, a negative value, or a value with a character that is
 * not an ASCII digit, stops the app start: this function throws
 * [IllegalStateException], with a message that names [variableName] and
 * never repeats [rawValue].
 *
 * This function is `internal`, so a test of this module can call it
 * directly (see `IngestRouteTest.kt`).
 */
internal fun positiveWholeNumberFromEnvironmentValue(rawValue: String?, variableName: String, defaultValue: Int): Int {
    if (rawValue == null) {
        return defaultValue
    }
    val trimmed = rawValue.trim()
    val parsedValue = trimmed.toIntOrNull()
    if (ASCII_DIGITS_PATTERN.matches(trimmed) && parsedValue != null && parsedValue > 0) {
        return parsedValue
    }
    throw IllegalStateException(
        "$variableName must hold a positive whole number of ASCII digits. The app start stops, " +
            "because a wrong proxy count can let a forged header choose the client address.",
    )
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
    @Suppress("DEPRECATION")
    val bytes = request.receiveChannel().readRemaining(maxBytes + 1).use { it.readByteArray() }
    return if (bytes.size > maxBytes) null else bytes.toString(Charsets.UTF_8)
}
