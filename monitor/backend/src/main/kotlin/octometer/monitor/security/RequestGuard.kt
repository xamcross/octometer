package octometer.monitor.security

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import octometer.monitor.config.MonitorConfig

// D12: the exact CSP text of the design.
private const val CONTENT_SECURITY_POLICY = "default-src 'self'; style-src 'self' 'unsafe-inline'; " +
    "object-src 'none'; base-uri 'self'; frame-ancestors 'none'"

// D12: the ng serve port, allowed in dev mode only.
private const val NG_SERVE_PORT = 4200

private val MUTATING_METHODS = setOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch, HttpMethod.Delete)

@Serializable
private data class RejectionBody(val error: String)

/**
 * The checks of D12, as one interceptor. A later route gets the same
 * protection, with no new code at that route, because the interceptor runs
 * on the Plugins phase, before the routing phase resolves a route.
 *
 * Order: the security response headers, then the Host check on each route,
 * then the Origin check and the content type check on a mutating method
 * (POST, PUT, PATCH, DELETE). A rejected request never reaches a route
 * handler, thus it changes no data. The server never installs the CORS
 * plugin, thus no response holds Access-Control-Allow-Origin.
 */
fun Application.installRequestGuard(config: MonitorConfig) {
    val allowedHosts = allowedHosts(config)
    val allowedOrigins = allowedOrigins(config)

    intercept(ApplicationCallPipeline.Plugins) {
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Content-Security-Policy", CONTENT_SECURITY_POLICY)
        if (call.request.path().startsWith("/api")) {
            call.response.header(HttpHeaders.CacheControl, "no-store")
        }

        val host = call.request.header(HttpHeaders.Host)?.lowercase()
        if (host == null || host !in allowedHosts) {
            call.respond(HttpStatusCode.Forbidden, RejectionBody("The Host header is missing or not allowed."))
            finish()
            return@intercept
        }

        if (call.request.httpMethod in MUTATING_METHODS) {
            val origin = call.request.header(HttpHeaders.Origin)?.lowercase()
            if (origin == null || origin !in allowedOrigins) {
                call.respond(HttpStatusCode.Forbidden, RejectionBody("The Origin header is missing or not allowed."))
                finish()
                return@intercept
            }
            if (call.request.contentType().withoutParameters() != ContentType.Application.Json) {
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

// D12: localhost:<port> and 127.0.0.1:<port>, plus the ng serve port in dev
// mode. The design names no IPv6 form, thus [::1]:<port> stays out; see the
// pull request text for this decision and its source.
private fun allowedHosts(config: MonitorConfig): Set<String> {
    val hosts = mutableSetOf("localhost:${config.port}", "127.0.0.1:${config.port}")
    if (config.mode == "dev") {
        hosts += "localhost:$NG_SERVE_PORT"
        hosts += "127.0.0.1:$NG_SERVE_PORT"
    }
    return hosts
}

private fun allowedOrigins(config: MonitorConfig): Set<String> {
    val origins = mutableSetOf("http://localhost:${config.port}", "http://127.0.0.1:${config.port}")
    if (config.mode == "dev") {
        origins += "http://localhost:$NG_SERVE_PORT"
        origins += "http://127.0.0.1:$NG_SERVE_PORT"
    }
    return origins
}
