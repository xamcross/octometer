package octometer.monitor.tracker

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import octometer.monitor.ErrorBody
import octometer.monitor.UNKNOWN_API_ROUTE_MESSAGE

/** The cache header of a request whose query `v` equals the current hash (design decision D53). */
private const val IMMUTABLE_CACHE_CONTROL = "public, max-age=31536000, immutable"

/** The cache header of each other request: a wrong or an absent `v` (the brief review of 2026-10-01, M1, M2). */
private const val SHORT_CACHE_CONTROL = "public, max-age=3600"

private val TRACKER_CONTENT_TYPE = ContentType.parse("text/javascript; charset=utf-8")

/**
 * `GET /tracker.js` (design decision D53 and its addendum of 2026-10-02,
 * issue #298, #331): the IIFE bundle of `kit/tracker`, with no CORS
 * header (a `<script src>` loads cross-origin with no CORS) and no
 * session (it carries no `/api/` prefix, thus `installSessionCheck`
 * never reaches it). The GET method is safe (D12), so
 * `installRequestGuard` runs no Origin check on it either; the Host
 * check still runs, and the public host is in the allow-list.
 * `OPTIONS /tracker.js` of `CollectRoute.kt` answers the preflight that
 * a browser never sends for a plain script tag anyway.
 *
 * The answer holds no header that depends on a request header other
 * than `If-None-Match` itself; a later header that depends on `Origin`
 * needs a `Vary: Origin` beside the `public` cache.
 *
 * [bundle] is `null` for a developer run with no `installDist` (the
 * brief review of 2026-10-01, B5); the route then answers 404.
 */
fun Route.trackerRoute(bundle: TrackerBundle?) {
    get("/tracker.js") {
        if (bundle == null) {
            call.respond(HttpStatusCode.NotFound, ErrorBody(UNKNOWN_API_ROUTE_MESSAGE))
            return@get
        }
        // The route reads `v` for one compare only; it writes `v` into
        // no header and no body (the addendum of 2026-10-02). A random
        // `v` makes a new key in a shared cache, and the bundle sits in
        // memory, so the origin cost stays small.
        val queryHash = call.request.queryParameters["v"]
        val cacheControl = if (queryHash == bundle.hash) IMMUTABLE_CACHE_CONTROL else SHORT_CACHE_CONTROL
        val etag = "\"${bundle.hash}\""
        call.response.header(HttpHeaders.CacheControl, cacheControl)
        call.response.header(HttpHeaders.ETag, etag)
        if (requestMatchesEtag(call.request.headers[HttpHeaders.IfNoneMatch], bundle.hash)) {
            call.respond(HttpStatusCode.NotModified)
            return@get
        }
        call.respondBytes(bundle.bytes, TRACKER_CONTENT_TYPE)
    }
}

/**
 * Compares the `If-None-Match` header with [hash] (the addendum of
 * 2026-10-02, M6). The header can hold several comma-separated values;
 * each one is trimmed, a `W/` prefix and the quotes are stripped, and
 * `*` matches any hash. The route never echoes the header value back.
 */
private fun requestMatchesEtag(ifNoneMatch: String?, hash: String): Boolean {
    if (ifNoneMatch == null) return false
    return ifNoneMatch.split(",").any { rawValue ->
        val value = rawValue.trim().removePrefix("W/").trim('"')
        value == "*" || value == hash
    }
}
