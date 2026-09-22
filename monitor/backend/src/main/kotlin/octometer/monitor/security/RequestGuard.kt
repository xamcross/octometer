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
 * - the Host check, on each route,
 * - the Origin check, on each request with a method other than GET and
 *   HEAD,
 * - the content type check, only on a request that holds a body.
 *
 * A rejected request never reaches a route handler, thus it changes no
 * data. The server never installs the CORS plugin, thus no response holds
 * Access-Control-Allow-Origin.
 */
fun Application.installRequestGuard(config: MonitorConfig) {
    val allowedHosts = allowedHosts(config)
    val allowedOrigins = allowedOrigins(config)

    intercept(ApplicationCallPipeline.Setup) {
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Content-Security-Policy", CONTENT_SECURITY_POLICY)
        if (isApiPath(call.request.path())) {
            call.response.header(HttpHeaders.CacheControl, "no-store")
        }

        val host = call.request.header(HttpHeaders.Host)?.lowercase()
        if (host == null || host !in allowedHosts) {
            call.respond(HttpStatusCode.Forbidden, RejectionBody("The Host header is missing or not allowed."))
            finish()
            return@intercept
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
// Correction round 1 of issue #38 (MINOR 1, security review): internal,
// not private, so octometer.monitor.frontend.staticFrontend can use the
// same rule and give a 404 of its own, instead of a second Cache-Control
// header and the wrong body.
internal fun isApiPath(rawPath: String): Boolean {
    val decoded = try {
        rawPath.decodeURLPart()
    } catch (badEscape: URLDecodeException) {
        return true
    }
    val normalised = Regex("/+").replace(decoded, "/")
    return normalised == "/api" || normalised.startsWith("/api/")
}

// D12: localhost:<port> and 127.0.0.1:<port>, plus the ng serve port in dev
// mode. The design names no IPv6 form, thus [::1]:<port> stays out; see the
// pull request text for this decision and its source.
private fun allowedHosts(config: MonitorConfig): Set<String> {
    val hosts = mutableSetOf("localhost:${config.port}", "127.0.0.1:${config.port}")
    if (config.port == HTTP_DEFAULT_PORT) {
        hosts += "localhost"
        hosts += "127.0.0.1"
    }
    if (config.mode == Mode.DEV.value) {
        hosts += "localhost:$NG_SERVE_PORT"
        hosts += "127.0.0.1:$NG_SERVE_PORT"
    }
    return hosts
}

private fun allowedOrigins(config: MonitorConfig): Set<String> {
    val origins = mutableSetOf("http://localhost:${config.port}", "http://127.0.0.1:${config.port}")
    if (config.port == HTTP_DEFAULT_PORT) {
        origins += "http://localhost"
        origins += "http://127.0.0.1"
    }
    if (config.mode == Mode.DEV.value) {
        origins += "http://localhost:$NG_SERVE_PORT"
        origins += "http://127.0.0.1:$NG_SERVE_PORT"
    }
    return origins
}
