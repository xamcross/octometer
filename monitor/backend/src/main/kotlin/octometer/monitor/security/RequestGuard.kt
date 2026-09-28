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
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import octometer.monitor.config.Mode
import octometer.monitor.config.MonitorConfig

// Issue #295 (D52): the header name of the temporary admin guard. #301
// removes the guard and this header.
private const val ADMIN_HEADER_NAME = "X-Octometer-Admin"

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
 * - the admin guard, on each route under `/api/`, when `adminToken` is
 *   set (issue #295, D52; a temporary measure until #301),
 * - the Origin check, on each request with a method other than GET and
 *   HEAD,
 * - the content type check, only on a request that holds a body.
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

        // Issue #295, brief review B2, B3: the temporary admin guard.
        // isApiPath already excludes /healthz and the static frontend,
        // so no extra path check is needed here. #301 removes this
        // block and the config key adminToken with it.
        if (config.adminToken.isNotBlank() && isApiPath(requestPath)) {
            val provided = call.request.header(ADMIN_HEADER_NAME)
            if (!constantTimeEquals(provided, config.adminToken)) {
                call.respond(HttpStatusCode.Unauthorized, RejectionBody("The $ADMIN_HEADER_NAME header is missing or not valid."))
                finish()
                return@intercept
            }
        }

        if (call.request.httpMethod !in SAFE_METHODS) {
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

// Issue #295, brief review B2: a constant-time compare of the admin
// header against the configured token, so a timing difference never
// narrows down the token one byte at a time.
private fun constantTimeEquals(provided: String?, expected: String): Boolean {
    if (provided == null) return false
    return MessageDigest.isEqual(provided.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
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
