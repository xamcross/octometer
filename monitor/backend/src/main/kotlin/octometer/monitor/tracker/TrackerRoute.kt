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
 * `GET /tracker.js` (design decision D53, issue #298): the IIFE bundle
 * of `kit/tracker`, with no CORS header (a `<script src>` loads
 * cross-origin with no CORS) and no session (it carries no `/api/`
 * prefix, thus `installSessionCheck` never reaches it). The GET method
 * is safe (D12), so `installRequestGuard` runs no Origin check on it
 * either; the Host check still runs, and the public host is in the
 * allow-list. `OPTIONS /tracker.js` of `CollectRoute.kt` answers the
 * preflight that a browser never sends for a plain script tag anyway.
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
        val queryHash = call.request.queryParameters["v"]
        val cacheControl = if (queryHash == bundle.hash) IMMUTABLE_CACHE_CONTROL else SHORT_CACHE_CONTROL
        call.response.header(HttpHeaders.CacheControl, cacheControl)
        call.response.header(HttpHeaders.ETag, "\"${bundle.hash}\"")
        call.respondBytes(bundle.bytes, TRACKER_CONTENT_TYPE)
    }
}
