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
import octometer.kit.core.ingest.AnonymousDailyCap
import octometer.kit.core.ingest.AnonymousKey
import octometer.kit.core.ingest.AnonymousMinuteLimiter
import octometer.kit.core.ingest.BotUserAgentFilter
import octometer.kit.core.ingest.EventFieldValidator
import octometer.kit.core.ingest.IngestEvent
import octometer.kit.core.ingest.IngestException
import octometer.kit.core.ingest.IngestPipeline
import octometer.kit.core.ingest.IngestRateLimiter
import octometer.kit.core.ingest.IngestSettings
import octometer.kit.core.ingest.RateLimitResult
import octometer.kit.core.store.DeletionResult
import octometer.kit.core.store.EventLogStore
import java.time.Clock
import java.time.Duration
import kotlin.coroutines.coroutineContext

/** The default path of the ingest route (design section 4.2, contract rule C12). */
public const val DEFAULT_INGEST_PATH: String = "/api/octometer/v1/clicks"

/** The maximum size of one request body, in bytes (contract rule C18). */
private const val MAX_BODY_BYTES: Long = 16 * 1024

/**
 * The maximum length of one client address value, in characters (issue
 * #33). A value above this length falls back to the remote address; see
 * [clientAddress]. The text of an IPv6 address needs at most 45
 * characters, so this cap stays well above every normal address text.
 */
private const val MAX_CLIENT_ADDRESS_LENGTH = 64

/**
 * The maximum length of the `User-Agent` text that the bot filter reads
 * (design decision D43, issue #117, Java review MAJOR 1). A client can
 * send a header value near the 8 KB header limit of the Netty engine.
 * This cap keeps one call of [BotUserAgentFilter.isBot] cheap, also
 * for a client already at its rate limit.
 */
private const val MAX_USER_AGENT_LENGTH = 512

/** One hour, in milliseconds (the throttle window of [BotDropLogThrottle]). */
private val BOT_DROP_LOG_THROTTLE_MILLIS = Duration.ofHours(1).toMillis()

/**
 * The exact `element` text of the first event of a session (contract
 * rule C38). [octometerIngestRoute] uses this text to tell apart a
 * click entry from a session-start entry for the two entry counters of
 * [AnonymousMinuteLimiter] (design decision D43, issue #116).
 */
private const val SESSION_START_ELEMENT = "octo:session-start"

/** The environment variable of the trusted proxy count (design decision D43, issue #116). */
private const val TRUSTED_PROXY_COUNT_VARIABLE = "OCTOMETER_TRUSTED_PROXY_COUNT"

/** The default of [TRUSTED_PROXY_COUNT_VARIABLE] (design decision D43, issue #116). */
private const val DEFAULT_TRUSTED_PROXY_COUNT = 1

/** Only an ASCII digit sets the trusted proxy count. A Unicode digit does not. */
private val ASCII_DIGITS_PATTERN = Regex("^[0-9]+$")

/**
 * A text form of one IPv4 address (four dot-separated numbers, each 0 to
 * 255).
 */
private val IPV4_ADDRESS_PATTERN = Regex(
    "^(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])(\\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}$",
)

/**
 * A text form of one IPv6 address, with the standard compressed ("::")
 * form and an embedded IPv4 tail.
 */
private val IPV6_ADDRESS_PATTERN = Regex(
    """
    ^(
    ([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}|
    ([0-9a-fA-F]{1,4}:){1,7}:|
    ([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|
    ([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|
    ([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}|
    ([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}|
    ([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|
    [0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})|
    :((:[0-9a-fA-F]{1,4}){1,7}|:)|
    ::(ffff(:0{1,4})?:)?((25[0-5]|(2[0-4]|1?[0-9])?[0-9])\.){3}(25[0-5]|(2[0-4]|1?[0-9])?[0-9])|
    ([0-9a-fA-F]{1,4}:){1,4}:((25[0-5]|(2[0-4]|1?[0-9])?[0-9])\.){3}(25[0-5]|(2[0-4]|1?[0-9])?[0-9])
    )$
    """,
    RegexOption.COMMENTS,
)

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
 * **The order of the checks (design decision D43, issue #117; issue
 * #116).** The route corrected this order on 2026-09-22. The rate
 * limiter now runs before the bot filter and any real body read too,
 * the original rule of issue #33. The route runs each check of one
 * request in this order, and it stops at the first one that answers:
 *
 * 1. the `Content-Type` header (415, contract rule C12);
 * 2. the rate limiter of design decision D20 (429, issue #33) — a
 *    client already at its limit never reaches step 3 or any step
 *    below;
 * 3. the anonymous per-minute request counter of design decision D43
 *    (429, issue #116). It needs no parsed entry, so it sits beside
 *    the rate limiter of step 2, before the bot filter and the body
 *    read;
 * 4. the bot filter of [BotUserAgentFilter] (204), on a maximum of 512
 *    characters of the `User-Agent` value;
 * 5. the body size (400, contract rule C18), the declared
 *    `Content-Length` only, with no body read;
 * 6. the real body read (400, contract rule C18, for a body above the
 *    limit that step 5 could not catch from its declared length alone)
 *    and the parse of the body (400, the field rules of
 *    `kit/jvm-core`);
 * 7. the anonymous per-minute click-entry counter and session-start
 *    counter of design decision D43 (429, issue #116). They need the
 *    parsed batch, to tell a click entry apart from an
 *    `octo:session-start` entry, so they run only here, after step 6;
 * 8. the design decision D19 drop: a request with no user id stores
 *    nothing and answers 204, when the app records no anonymous click;
 * 9. the daily anonymous caps of design decision D43 (204), for a
 *    request with no user id, when the app records an anonymous click;
 * 10. the store, with the event cap of design decision D21 inside it.
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
 *   route. The route reads the element that [trustedProxyCount] counts
 *   from the right of that header line. A `null` value, a request with no
 *   such header, a value above 64 characters, and a value with no IPv4 or
 *   IPv6 address form, each fall back to the remote address of the
 *   connection. Set this option only behind a proxy that appends the real
 *   client address this way; see `kit/jvm-ktor/README.md`.
 * @param trustedProxyCount the position, counted from the right of the
 *   header list of [clientIpHeaderName], of the address to trust (design
 *   decision D20, issue #116). The default reads
 *   `OCTOMETER_TRUSTED_PROXY_COUNT` once, at 1 with no such variable. A
 *   count above the length of the header list falls back to the remote
 *   address, the same as a value with no address form. A text value, a
 *   zero, or a negative value stops the app start; the error message
 *   never repeats the raw value.
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
    val botDropLogThrottle = BotDropLogThrottle(clock)

    post(ingestPath) {
        try {
            // 1. Content type (415, contract rule C12). This check reads
            // no body, so a request with a wrong content type stays
            // cheap.
            if (!hasJsonContentType(call)) {
                call.respond(HttpStatusCode.UnsupportedMediaType)
                return@post
            }

            // resolveUserId runs here, on the coroutine of the call,
            // before the store call moves to storeDispatcher (rule of
            // the app documentation above). It runs before the rate
            // limit check. The route still runs it for a rejected
            // request, so keep that function short, as its own KDoc
            // already asks.
            val userId = resolveUserId(call)

            // The route reads the client address header only for a
            // request with no user id, and only once: the rate
            // limiter, the anonymous per-minute limiter, and the daily
            // cap all read the same normalised address below, so this
            // call would otherwise waste a repeated header lookup.
            val clientAddr = if (userId == null) clientAddress(call, clientIpHeaderName, trustedProxyCount) else null

            // 2. The rate limiter (429, design decision D20, issue
            // #33). This runs before the bot filter and the real body
            // read (steps 4 and 6). A client already at its limit
            // never reaches the filter, that read, or the parse. Issue
            // #117 restated this original rule of issue #33 on
            // 2026-09-22.
            val rateLimitResult = if (userId != null) {
                rateLimiter.check(userId, "")
            } else {
                rateLimiter.check(null, clientAddr!!)
            }
            if (rateLimitResult == RateLimitResult.LIMITED) {
                call.respond(HttpStatusCode.TooManyRequests)
                return@post
            }

            // 3. The anonymous per-minute request counter (429,
            // design decision D43, issue #116). It sits beside the
            // rate limiter of step 2, before the bot filter and the
            // real body read, because this counter needs no parsed
            // entry: one request is one count, with no need to read
            // the body. It runs only for a request with no user id,
            // when the app records an anonymous click.
            // anonymousMinuteKey stays null otherwise, so the two
            // entry counters of step 7 below skip their own check for
            // the same request.
            val anonymousMinuteKey = if (userId == null && settings.recordAnonymousClicks()) {
                AnonymousKey.of(clientAddr!!)
            } else {
                null
            }
            if (anonymousMinuteKey != null && !minuteLimiter.checkRequest(anonymousMinuteKey)) {
                call.respond(HttpStatusCode.TooManyRequests)
                return@post
            }

            // 4. The bot filter (204, design decision D43, issue #117).
            // This check reads a maximum of MAX_USER_AGENT_LENGTH
            // characters of the header value, so a long value never
            // makes the filter costly (Java review MAJOR 1). The kit
            // stores no User-Agent value: the log line below holds no
            // header value.
            val userAgentValue = call.request.header(HttpHeaders.UserAgent)?.take(MAX_USER_AGENT_LENGTH)
            if (BotUserAgentFilter.isBot(userAgentValue)) {
                val dropCount = botDropLogThrottle.recordDrop()
                if (dropCount != null) {
                    call.application.log.debug(
                        "The Octometer ingest route dropped {} robot batches in the last hour " +
                            "(design decision D43).",
                        dropCount,
                    )
                }
                call.respond(HttpStatusCode.NoContent)
                return@post
            }

            // 5. Body size (400, contract rule C18): the declared
            // Content-Length only, with no body read. A request with
            // no declared length, or a length at or under the limit,
            // passes here. The real read below (step 6) still enforces
            // the same limit for such a request.
            if (!hasAcceptableDeclaredLength(call, MAX_BODY_BYTES)) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }

            // 6. The body read (400, contract rule C18: a body above
            // the limit with no declared length, or a declared length
            // that understates the real body) and the parse (400, the
            // field rules of `kit/jvm-core`).
            val rawBody = call.receiveLimitedText(MAX_BODY_BYTES)
            if (rawBody == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            val events = try {
                IngestPipeline.process(rawBody, clock, settings)
            } catch (cause: IngestException) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }

            // 7. The anonymous per-minute click-entry counter and
            // session-start counter (429, design decision D43, issue
            // #116). They need the parsed batch, to tell a click entry
            // apart from an `octo:session-start` entry. They run only
            // here, after the parse of step 6, never before it.
            // anonymousMinuteKey is null for a signed-in user, and for
            // a request when the app records no anonymous click, so
            // this check then never runs for such a request.
            if (anonymousMinuteKey != null && events.isNotEmpty()) {
                var clickEntryCount = 0
                var sessionStartCount = 0
                for (event in events) {
                    if (event.element() == SESSION_START_ELEMENT) {
                        sessionStartCount++
                    } else {
                        clickEntryCount++
                    }
                }
                if (!minuteLimiter.checkEntries(anonymousMinuteKey, clickEntryCount, sessionStartCount)) {
                    call.respond(HttpStatusCode.TooManyRequests)
                    return@post
                }
            }

            // 8. The design decision D19 drop: a request with no user
            // id stores nothing, when the app records no anonymous
            // click.
            if (userId == null && !settings.recordAnonymousClicks()) {
                call.respond(HttpStatusCode.NoContent)
                return@post
            }

            // 9. The daily anonymous caps (204, design decision D43,
            // issue #117), for a request with no user id, when the app
            // records an anonymous click. An empty batch needs no
            // check: it already stores nothing, the same as a dropped
            // batch. anonymousMinuteKey already holds the same
            // normalised key here. Step 8 above already returned for a
            // request with no user id when the app records no
            // anonymous click, so anonymousMinuteKey is not null here.
            if (userId == null && events.isNotEmpty()) {
                if (!dailyCap.check(anonymousMinuteKey!!, events.size)) {
                    call.respond(HttpStatusCode.NoContent)
                    return@post
                }
            }

            if (events.isEmpty()) {
                // Contract rule C13 sets no minimum for the clicks
                // array; an empty batch stores nothing (design section
                // 4.2).
                call.respond(HttpStatusCode.NoContent)
                return@post
            }

            // 10. The store, with the event cap of design decision D21
            // inside it. A userId that breaks contract rule C6 is a
            // defect of the app, not of the client; it gives 500 below,
            // never 400.
            try {
                EventFieldValidator.validateUserId(userId)
            } catch (cause: IngestException) {
                throw IllegalStateException(
                    "The resolveUserId function of the app returned a userId that breaks contract rule C6.",
                    cause,
                )
            }
            val defectSafeStore = DefectSafeEventLogStore(store)
            withContext(storeDispatcher) {
                defectSafeStore.append(events, userId)
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
 * Throttles the DEBUG line of a bot-filter drop to one line for each
 * elapsed hour (design decision D43, issue #117, security review M3).
 * With no throttle, a robot flood would write one line for each
 * dropped request, ahead of the rate limiter of step 2. [recordDrop]
 * counts every drop. It reports the drop count only on the one call
 * that must write a new line.
 *
 * One route builds one instance, held for the life of the route, the
 * form of [AnonymousDailyCap] and [IngestRateLimiter]. This class is
 * `internal`, so a test of this module can build one on its own.
 */
internal class BotDropLogThrottle(private val clock: Clock) {
    private val lock = Any()
    private var lastLoggedAtMillis = Long.MIN_VALUE
    private var dropCountSinceLastLog = 0L

    /**
     * Records one bot-filter drop. It returns the drop count since the
     * last written line, on the one call that must write a new line.
     * It returns `null` on every other call; the caller then writes no
     * line.
     */
    fun recordDrop(): Long? {
        val now = clock.millis()
        synchronized(lock) {
            dropCountSinceLastLog += 1
            if (lastLoggedAtMillis != Long.MIN_VALUE && now - lastLoggedAtMillis < BOT_DROP_LOG_THROTTLE_MILLIS) {
                return null
            }
            val dueDropCount = dropCountSinceLastLog
            lastLoggedAtMillis = now
            dropCountSinceLastLog = 0
            return dueDropCount
        }
    }
}

/**
 * Reads the client address of one call for the rate limiter (design
 * decision D20, issue #33, step 3) and for the anonymous per-minute
 * limiter (design decision D43, issue #116).
 *
 * It reads the header that [headerName] names, when [headerName] is not
 * `null` and the request holds that header. A request can repeat one
 * header name as more than one header line; this function reads the
 * *last* line ([io.ktor.http.Headers.getAll]), because a trusted proxy
 * that adds its own line appends it after the lines of the client. Inside
 * that last line, this function reads the element that [trustedProxyCount]
 * counts from the right, one-based (design decision D20, issue #116): the
 * nearest proxy appends its peer address as the last element, so
 * [trustedProxyCount] of 1 (the default) reads that last element, the
 * address that the nearest proxy itself observed. A [trustedProxyCount]
 * of 2 reads the second-from-right element, the address that the second
 * proxy of the chain observed, and so on for one app behind more than
 * one trusted proxy.
 *
 * A value above [MAX_CLIENT_ADDRESS_LENGTH] characters, a value with no
 * IPv4 or IPv6 address form, a [trustedProxyCount] above the length of
 * the comma-separated list, a request with no such header, and a `null`
 * [headerName], each fall back to the remote address of the connection
 * ([io.ktor.server.request.ApplicationRequest.local]).
 *
 * An app must set [headerName] only behind a proxy that appends the real
 * client address this way. See `kit/jvm-ktor/README.md`.
 */
private fun clientAddress(call: ApplicationCall, headerName: String?, trustedProxyCount: Int): String {
    val elements = headerName
        ?.let { call.request.headers.getAll(it)?.lastOrNull() }
        ?.split(',')
    val headerValue = elements
        ?.let { it.getOrNull(it.size - trustedProxyCount) }
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= MAX_CLIENT_ADDRESS_LENGTH && looksLikeAnIpAddress(it) }
    return headerValue ?: call.request.local.remoteAddress
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
 * True when [value] has the text form of an IPv4 address or an IPv6
 * address (issue #33, the header rule of design decision D20). This
 * function checks the text form only; it makes no network call and it
 * resolves no name.
 */
private fun looksLikeAnIpAddress(value: String): Boolean =
    IPV4_ADDRESS_PATTERN.matches(value) || IPV6_ADDRESS_PATTERN.matches(value)

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
 * True when the declared `Content-Length` header of [call], at or under
 * [maxBytes], lets the route skip a real body read (contract rule C18,
 * design decision D43, issue #117). An absent header, and a header this
 * function cannot parse as a whole number, both pass here; [ApplicationCall.receiveLimitedText]
 * still enforces [maxBytes] for a body with no declared length, or a
 * declared length that understates the real body. This function reads
 * no byte of the body.
 */
private fun hasAcceptableDeclaredLength(call: ApplicationCall, maxBytes: Long): Boolean {
    val declaredLength = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
    return declaredLength == null || declaredLength <= maxBytes
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
