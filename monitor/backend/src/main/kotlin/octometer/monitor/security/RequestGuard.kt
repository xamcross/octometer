package octometer.monitor.security

import io.ktor.http.BadContentTypeFormatException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLDecodeException
import io.ktor.http.decodeURLPart
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import java.net.URI
import java.net.URISyntaxException
import kotlinx.serialization.Serializable
import octometer.monitor.config.Mode
import octometer.monitor.config.MonitorConfig

// D12: the exact CSP text of the design.
private const val CONTENT_SECURITY_POLICY = "default-src 'self'; style-src 'self' 'unsafe-inline'; " +
    "object-src 'none'; base-uri 'self'; frame-ancestors 'none'"

// D12: the ng serve port, allowed in dev mode only.
private const val NG_SERVE_PORT = 4200

// A browser omits the port in the Host header and in the Origin header for
// the default port of the scheme. The monitor speaks plain HTTP, thus only
// port 80 needs the bare form.
private const val HTTP_DEFAULT_PORT = 80

// Step 2 of issue #5: GET and HEAD are the safe methods. Each other method,
// known today or added by a later route, needs the Origin check.
private val SAFE_METHODS = setOf(HttpMethod.Get, HttpMethod.Head)

@Serializable
private data class RejectionBody(val error: String)

/**
 * The checks of D12, as one interceptor. A later route gets the same
 * protection, with no new code at that route. The interceptor runs on the
 * Setup phase, the first phase of the pipeline, thus it also runs before a
 * plugin that a later change installs.
 *
 * The order:
 * - the security response headers,
 * - the Host check, on each route except `/healthz` (D56),
 * - the Origin check, on each request with a method other than GET and
 *   HEAD,
 * - the content type check, only on a request that holds a body.
 *
 * `octometer.monitor.accounts.installSessionCheck` runs after this
 * function, as a second interceptor of the same `Setup` phase (issue
 * #301, D52): the session rule of every `/api/` route, except the two
 * sign-in routes.
 *
 * A rejected request never reaches a route handler, thus it changes no
 * data. The server never installs the CORS plugin, thus no response holds
 * Access-Control-Allow-Origin.
 *
 * [env] gives the process environment. Fly sets `FLY_APP_NAME`; the Host
 * and the Origin allow-lists then also take the Fly host of the app
 * (D52, brief review M5, M6). A test gives its own map.
 */
fun Application.installRequestGuard(config: MonitorConfig, env: Map<String, String> = System.getenv()) {
    val allowedHosts = allowedHosts(config, env)
    val allowedOrigins = allowedOrigins(config, env)

    intercept(ApplicationCallPipeline.Setup) {
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Content-Security-Policy", CONTENT_SECURITY_POLICY)
        // Issue #301, D52, brief review m4: no-referrer on every
        // response, not only the two sign-in routes of the maintainer's
        // first note. A mail scanner, or a shared link, then never
        // carries the sign-in token or the session cookie in a Referer
        // header to a third site.
        call.response.header("Referrer-Policy", "no-referrer")
        val requestPath = call.request.path()
        if (isApiPath(requestPath)) {
            call.response.header(HttpHeaders.CacheControl, "no-store")
        }

        // D56: /healthz skips the Host check, so a load balancer with no
        // Host header of its own can still reach it. isHealthzPath fails
        // closed on a bad escape: it then returns false, and the Host
        // check below still runs.
        if (!isHealthzPath(requestPath)) {
            val host = call.request.header(HttpHeaders.Host)?.lowercase()
            if (host == null || host !in allowedHosts) {
                call.respond(HttpStatusCode.Forbidden, RejectionBody("The Host header is missing or not allowed."))
                finish()
                return@intercept
            }
        }

        // Issue #300 (D55, the correction round of 2026-10-01, B1): the
        // erasure route's secret path carries the header
        // X-Octometer-Secret and no Origin at all; a server of the app
        // calls it directly, never a browser. isErasureSecretRequest
        // fails closed on a bad escape, the same rule as isCollectorPath.
        if (call.request.httpMethod !in SAFE_METHODS && !isCollectorPath(requestPath) &&
            !isErasureSecretRequest(call.request, requestPath)
        ) {
            val origin = call.request.header(HttpHeaders.Origin)?.lowercase()
            if (origin == null || origin !in allowedOrigins) {
                call.respond(HttpStatusCode.Forbidden, RejectionBody("The Origin header is missing or not allowed."))
                finish()
                return@intercept
            }

            if (hasBody(call.request)) {
                val requestContentType = try {
                    call.request.contentType().withoutParameters()
                } catch (badFormat: BadContentTypeFormatException) {
                    null
                }
                if (requestContentType != ContentType.Application.Json) {
                    call.respond(
                        HttpStatusCode.UnsupportedMediaType,
                        RejectionBody("Set the Content-Type header to application/json."),
                    )
                    finish()
                    return@intercept
                }
            }
        }
    }
}

// A request holds a body when it carries Content-Length above 0, or a
// Transfer-Encoding header (a chunked body gives no Content-Length).
private fun hasBody(request: ApplicationRequest): Boolean {
    val contentLength = request.header(HttpHeaders.ContentLength)?.toLongOrNull() ?: 0L
    return contentLength > 0L || request.header(HttpHeaders.TransferEncoding) != null
}

// The routing plugin decodes the request target and collapses a repeated
// slash before it matches a route. This test must do the same, else a raw
// target such as "//api/health" or "/%61pi/health" reaches the health
// route and drops Cache-Control: no-store by mistake. A bad escape fails
// closed: the header still goes on the response, because the extra header
// on a non-API response costs nothing, and a missing header on an API
// response can leak a cached copy of personal data.
//
// Correction round 1 of issue #38 (MINOR 1, security review): this
// function is internal, not private. octometer.monitor.frontend.staticFrontend
// can then use the same rule. It gives a 404 of its own, not a second
// Cache-Control header and the wrong body.
internal fun isApiPath(rawPath: String): Boolean {
    val decoded = try {
        rawPath.decodeURLPart()
    } catch (badEscape: URLDecodeException) {
        return true
    }
    val normalised = Regex("/+").replace(decoded, "/")
    return normalised == "/api" || normalised.startsWith("/api/")
}

// D56, brief review B3: the same two steps as isApiPath (the decode step,
// the collapse step), with an exact match of "/healthz". A bad escape
// fails closed: it returns false, so the caller keeps the Host check.
internal fun isHealthzPath(rawPath: String): Boolean {
    val decoded = try {
        rawPath.decodeURLPart()
    } catch (badEscape: URLDecodeException) {
        return false
    }
    val normalised = Regex("/+").replace(decoded, "/")
    return normalised == "/healthz"
}

// Issue #297 (D50, the brief review of 2026-10-01, B1): the same two
// steps as isHealthzPath, with an exact match of "/collect" or
// "/tracker.js". A bad escape fails closed: it returns false, so the
// caller keeps the Origin check and the content type check.
internal fun isCollectorPath(rawPath: String): Boolean {
    val decoded = try {
        rawPath.decodeURLPart()
    } catch (badEscape: URLDecodeException) {
        return false
    }
    val normalised = Regex("/+").replace(decoded, "/")
    return normalised == "/collect" || normalised == "/tracker.js"
}

/** The header of the erasure route's secret path (design decision D55, issue #300). */
internal const val ERASURE_SECRET_HEADER = "X-Octometer-Secret"

// Issue #300 (D55, the correction round of 2026-10-01, B1): the method
// DELETE, the path /api/apps/{id}/events on a pattern match (not the
// exact set of isApiPath), and the header X-Octometer-Secret present.
// The header present means the secret decides the request; a missing
// header keeps the normal Origin check and the normal session check.
private val ERASURE_EVENTS_PATH = Regex("^/api/apps/[^/]+/events$")

internal fun isErasureSecretRequest(request: ApplicationRequest, rawPath: String): Boolean =
    request.httpMethod == HttpMethod.Delete &&
        request.header(ERASURE_SECRET_HEADER) != null &&
        isErasureEventsPath(rawPath)

// The same two steps as isHealthzPath (the decode step, the collapse
// step), with a pattern match instead of an exact match: the path
// carries the app id. A bad escape fails closed: it returns false, so
// the caller keeps the Origin check.
internal fun isErasureEventsPath(rawPath: String): Boolean {
    val decoded = try {
        rawPath.decodeURLPart()
    } catch (badEscape: URLDecodeException) {
        return false
    }
    val normalised = Regex("/+").replace(decoded, "/")
    return ERASURE_EVENTS_PATH.matches(normalised)
}

// The host of config.publicUrl, for example "app.0ct0meter.com" from
// "https://app.0ct0meter.com". A blank publicUrl, or one with no valid
// host, gives null; the local run and the desktop app set no publicUrl.
private fun publicUrlHost(config: MonitorConfig): String? {
    if (config.publicUrl.isBlank()) return null
    return try {
        URI(config.publicUrl).host?.lowercase()
    } catch (malformed: URISyntaxException) {
        null
    }
}

// D12: localhost:<port> and 127.0.0.1:<port>, plus the ng serve port in dev
// mode. The design names no IPv6 form, thus [::1]:<port> stays out; see the
// pull request text for this decision and its source.
//
// D52, brief review M5 (issue #295): a browser behind Fly sends the Host
// header with no port, for example "app.0ct0meter.com". This adds the
// bare host of publicUrl, the bare Fly host, and each one with ":443",
// because a browser can still send the default HTTPS port explicitly.
private fun allowedHosts(config: MonitorConfig, env: Map<String, String>): Set<String> {
    val hosts = mutableSetOf("localhost:${config.port}", "127.0.0.1:${config.port}")
    if (config.port == HTTP_DEFAULT_PORT) {
        hosts += "localhost"
        hosts += "127.0.0.1"
    }
    if (config.mode == Mode.DEV.value) {
        hosts += "localhost:$NG_SERVE_PORT"
        hosts += "127.0.0.1:$NG_SERVE_PORT"
    }
    publicUrlHost(config)?.let { host ->
        hosts += host
        hosts += "$host:443"
    }
    env["FLY_APP_NAME"]?.let { appName ->
        val flyHost = "$appName.fly.dev"
        hosts += flyHost
        hosts += "$flyHost:443"
    }
    return hosts
}

// D52, brief review M6 (issue #295): the dashboard origin behind Fly, so a
// POST or a DELETE from the deployed dashboard passes.
private fun allowedOrigins(config: MonitorConfig, env: Map<String, String>): Set<String> {
    val origins = mutableSetOf("http://localhost:${config.port}", "http://127.0.0.1:${config.port}")
    if (config.port == HTTP_DEFAULT_PORT) {
        origins += "http://localhost"
        origins += "http://127.0.0.1"
    }
    if (config.mode == Mode.DEV.value) {
        origins += "http://localhost:$NG_SERVE_PORT"
        origins += "http://127.0.0.1:$NG_SERVE_PORT"
    }
    if (config.publicUrl.isNotBlank()) {
        origins += config.publicUrl.lowercase().removeSuffix("/")
    }
    env["FLY_APP_NAME"]?.let { appName ->
        origins += "https://$appName.fly.dev"
    }
    return origins
}
