package octometer.monitor.collect

import io.ktor.http.BadContentTypeFormatException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import java.time.Clock
import java.util.regex.Pattern
import kotlinx.io.readByteArray
import octometer.kit.core.ingest.BotUserAgentFilter
import octometer.kit.core.ingest.IngestException
import octometer.kit.core.ingest.IngestEvent
import octometer.kit.core.ingest.IngestPipeline
import octometer.kit.core.ingest.IngestSettings
import octometer.kit.core.path.DefaultRoutePattern
import octometer.monitor.security.OriginCheck

/** The body limit of design decision D50, before the parse (contract rule C18). */
private const val MAX_BODY_BYTES: Long = 16 * 1024

/** The header truncation of the bot filter, the same bound as `IngestRequestProcessor` of `kit/jvm-core`. */
private const val MAX_USER_AGENT_LENGTH = 512

/** Contract rule C38's exact session-start text (`IngestPipeline.SESSION_START_ELEMENT` is package-private). */
private const val SESSION_START_ELEMENT = "octo:session-start"

/** The CORS headers of design decision D50 (the brief review of 2026-10-01, B2). */
private const val ALLOWED_HEADERS = "Content-Type, X-Octometer-Key"
private const val ALLOWED_METHOD = "POST"

/**
 * The Private Network Access header pair (not in the brief review; a
 * defect this story's own end-to-end scenario found). A browser treats
 * one loopback port as a different "network" than another loopback
 * port, the same rule it applies to a public page that calls a private
 * server. Chromium's preflight then carries
 * [PRIVATE_NETWORK_REQUEST_HEADER], and it blocks the real request with
 * no answer of [PRIVATE_NETWORK_ALLOW_HEADER] on the preflight. The
 * hosted collector and the dashboard app each have a public address in
 * production, so this header matters for a local run and for this
 * suite only; it is harmless on a public address too.
 */
private const val PRIVATE_NETWORK_REQUEST_HEADER = "Access-Control-Request-Private-Network"
private const val PRIVATE_NETWORK_ALLOW_HEADER = "Access-Control-Allow-Private-Network"
private const val MAX_AGE_SECONDS = "86400"

/**
 * The exact length of an app key: 32 random bytes, base64url without
 * padding (`octometer.monitor.accounts.newToken`), so this length is
 * fixed, never a range.
 */
private const val APP_KEY_LENGTH = 43

/** The base64url alphabet of an app key, with no padding character (MAJOR 1 of the review of pull request #327). */
private val APP_KEY_PATTERN: Pattern = Pattern.compile("^[A-Za-z0-9_-]{$APP_KEY_LENGTH}$")

/**
 * True when [appKey] has the exact shape of a real app key (MAJOR 1):
 * the base64url alphabet, at the fixed length of a real key. This check
 * runs with no map read and no SQLite statement, so a request with an
 * obviously invented key never reaches [AppKeyCache.lookup].
 */
private fun isPlausibleAppKey(appKey: String): Boolean = APP_KEY_PATTERN.matcher(appKey).matches()

/**
 * The dependencies of [collectRoute], one instance for the life of the
 * application (issue #297). [clientIpHeaderName] is `config.clientIpHeader`
 * (`Fly-Client-IP` on Fly); a blank value gives the remote address for
 * every request.
 */
class CollectorRuntime(
    val appKeyCache: AppKeyCache,
    val store: CollectorStore,
    val dailyEventCountStore: DailyEventCountStore,
    val writeStopGate: WriteStopGate,
    val rateLimiting: CollectorRateLimiting,
    val clientIpHeaderName: String,
    val clock: Clock = Clock.systemUTC(),
)

/**
 * The collector route of design decision D50: `POST /collect`, and the
 * CORS preflight of `OPTIONS /collect` and `OPTIONS /tracker.js` (the
 * brief review of 2026-10-01, B2; `/tracker.js` itself comes with issue
 * #298). `octometer.monitor.security.installRequestGuard` already skips
 * the Host check's one exception and the Origin check for these two
 * paths (`isCollectorPath`); this function runs its own Origin check
 * (against the one matched app) and its own content type check (415).
 */
fun Route.collectRoute(runtime: CollectorRuntime) {
    options("/collect") { respondToPreflight(call, runtime) }
    options("/tracker.js") { respondToPreflight(call, runtime) }

    post("/collect") {
        val appKey = call.request.header("X-Octometer-Key")
        if (appKey == null || !isPlausibleAppKey(appKey)) {
            // MAJOR 1 of the review of pull request #327: a cheap
            // reject, with no map read and no SQLite statement, before
            // this route ever calls AppKeyCache.lookup.
            call.respond(HttpStatusCode.NoContent)
            return@post
        }

        val clientIpHeaderLines = if (runtime.clientIpHeaderName.isBlank()) {
            emptyList()
        } else {
            call.request.headers.getAll(runtime.clientIpHeaderName) ?: emptyList()
        }
        val clientAddress = clientAddressOf(clientIpHeaderLines, call.request.local.remoteAddress)

        if (!runtime.rateLimiting.minuteLimiter.checkRequest(clientAddress)) {
            // MAJOR 1: the per-address rate limit runs before the app
            // key lookup, keyed on the client address alone (the app is
            // not known yet). One client address can then never force a
            // SQLite read for each of a flood of different invented
            // keys; this gate bounds that cost, with no app known at
            // all.
            call.respond(HttpStatusCode.TooManyRequests)
            return@post
        }

        val app = runtime.appKeyCache.lookup(appKey)
        if (app == null) {
            // Design decision D50: an unknown key answers 204, with no
            // body. A probe learns nothing.
            call.respond(HttpStatusCode.NoContent)
            return@post
        }

        val originHeader = call.request.header(HttpHeaders.Origin)
        if (!OriginCheck.matches(originHeader, app.origins)) {
            // Design decision D50: an unlisted origin answers 204, with
            // no CORS header. A program outside a browser sets any
            // Origin value; the write stop and the caps are the real
            // controls (D50).
            call.respond(HttpStatusCode.NoContent)
            return@post
        }
        // The answer of a matched origin always carries this header
        // (the brief review of 2026-10-01, B2), so the tracker's own
        // fetch (mode: 'cors') can read the 204, the 400, the 415, and
        // the 429 answers below.
        call.response.header(HttpHeaders.AccessControlAllowOrigin, originHeader!!)

        val requestContentType = try {
            call.request.contentType().withoutParameters()
        } catch (badFormat: BadContentTypeFormatException) {
            null
        }
        if (requestContentType != ContentType.Application.Json) {
            call.respond(HttpStatusCode.UnsupportedMediaType)
            return@post
        }

        if (runtime.writeStopGate.isWriteStopped()) {
            // Design decision D50: the cluster-wide write stop drops
            // every write above 450 MB of app_* storage.
            call.respond(HttpStatusCode.NoContent)
            return@post
        }

        val declaredLength = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
        if (declaredLength != null && declaredLength > MAX_BODY_BYTES) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }

        val rateLimitKey = runtime.rateLimiting.keyOf(app.appId, clientAddress)
        runtime.rateLimiting.recordRequest(rateLimitKey)

        val userAgent = call.request.headers.getAll(HttpHeaders.UserAgent)?.lastOrNull()
        val truncatedUserAgent = userAgent?.substring(0, minOf(userAgent.length, MAX_USER_AGENT_LENGTH))
        if (BotUserAgentFilter.isBot(truncatedUserAgent)) {
            call.respond(HttpStatusCode.NoContent)
            return@post
        }

        if (!runtime.rateLimiting.minuteLimiter.checkRequest(rateLimitKey)) {
            call.respond(HttpStatusCode.TooManyRequests)
            return@post
        }

        val rawBody = call.receiveLimitedText(MAX_BODY_BYTES)
        if (rawBody == null) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }

        // Issue #300 (design decision D54): the collector is the one
        // caller that sets acceptBodyUserId, so the body's own userId
        // field reaches the stored event (contract rules C16, C32 keep
        // the kit ingest route unchanged).
        val settings = IngestSettings(true, app.routePatternMatcher).withAcceptBodyUserId(true)
        val batch = try {
            IngestPipeline.processBatch(rawBody, runtime.clock, settings, DefaultRoutePattern::apply)
        } catch (invalid: IngestException) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }
        val events = batch.events()
        val userId = batch.userId()

        if (events.isNotEmpty()) {
            val (clickCount, sessionStartCount) = countEntries(events)
            if (!runtime.rateLimiting.minuteLimiter.checkEntries(rateLimitKey, clickCount, sessionStartCount)) {
                call.respond(HttpStatusCode.TooManyRequests)
                return@post
            }
        }

        if (events.isEmpty()) {
            // Contract rule C13: an empty batch stores nothing.
            call.respond(HttpStatusCode.NoContent)
            return@post
        }

        if (!runtime.rateLimiting.dailyCapOf(app.appId).check(rateLimitKey, events.size)) {
            runtime.dailyEventCountStore.recordDrop(app.appId)
            call.respond(HttpStatusCode.NoContent)
            return@post
        }
        if (!runtime.dailyEventCountStore.tryIncrement(app.appId, events.size)) {
            runtime.dailyEventCountStore.recordDrop(app.appId)
            call.respond(HttpStatusCode.NoContent)
            return@post
        }

        try {
            runtime.store.write(app.appId, app.databaseName, events, userId)
        } catch (writeFailure: RuntimeException) {
            // Design decision D15: the log holds the exception class
            // only, never a host, a database name, or an event field.
            // D50 states no 500 answer for this route; a write failure
            // answers 204, the same as a dropped batch, so a probe
            // learns nothing either way.
            call.application.log.warn(
                "The collector could not write a batch. The exception class is {}.",
                writeFailure.javaClass.simpleName,
            )
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

/**
 * Answers a CORS preflight of `OPTIONS /collect` or `OPTIONS
 * /tracker.js` (the brief review of 2026-10-01, B2). A browser's
 * preflight carries no `X-Octometer-Key` value, so this function checks
 * the request's `Origin` against the union of every registered app's
 * origins (the 10-second cache of [AppKeyCache.allOrigins]), not one
 * app. An unlisted origin gets 204 with no CORS header.
 */
private suspend fun respondToPreflight(call: ApplicationCall, runtime: CollectorRuntime) {
    val originHeader = call.request.header(HttpHeaders.Origin)
    val allOrigins = runtime.appKeyCache.allOrigins()
    if (OriginCheck.matches(originHeader, allOrigins.toList())) {
        call.response.header(HttpHeaders.AccessControlAllowOrigin, originHeader!!)
        call.response.header(HttpHeaders.AccessControlAllowHeaders, ALLOWED_HEADERS)
        call.response.header(HttpHeaders.AccessControlAllowMethods, ALLOWED_METHOD)
        call.response.header(HttpHeaders.AccessControlMaxAge, MAX_AGE_SECONDS)
        if (call.request.header(PRIVATE_NETWORK_REQUEST_HEADER) == "true") {
            call.response.header(PRIVATE_NETWORK_ALLOW_HEADER, "true")
        }
    }
    call.respond(HttpStatusCode.NoContent)
}

/** The click entry count and the `octo:session-start` entry count of [events] (design decision D43). */
private fun countEntries(events: List<IngestEvent>): Pair<Int, Int> {
    var clickCount = 0
    var sessionStartCount = 0
    for (event in events) {
        if (event.element() == SESSION_START_ELEMENT) sessionStartCount++ else clickCount++
    }
    return clickCount to sessionStartCount
}

/**
 * Reads the request body as text, with a limit of [maxBytes] raw bytes
 * (contract rule C18), the same streamed-read rule as
 * `octometer.kit.ktor.receiveLimitedText` of `kit/jvm-ktor` (a private
 * function of a different Gradle module). `readRemaining(Long)` is
 * deprecated in Ktor 3.6.0, in favor of `readBuffer(Long)`; this
 * function keeps the deprecated call, confirmed to still work at this
 * pinned Ktor version, the same note as the kit's own README.
 */
@Suppress("DEPRECATION")
private suspend fun ApplicationCall.receiveLimitedText(maxBytes: Long): String? {
    val bytes = request.receiveChannel().readRemaining(maxBytes + 1).use { it.readByteArray() }
    return if (bytes.size > maxBytes) null else bytes.toString(Charsets.UTF_8)
}
