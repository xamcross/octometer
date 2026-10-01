package octometer.monitor.accounts

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import octometer.monitor.ErrorBody
import octometer.monitor.config.MonitorConfig

private const val SESSION_MAX_AGE_SECONDS = 30L * 24 * 60 * 60
private const val CHECK_YOUR_MAIL_MESSAGE = "Check your mail."
private const val INVALID_LINK_MESSAGE = "The link is not valid. Request a new one."
private const val BAD_BODY_MESSAGE = "The body is not a valid request."

@Serializable
private data class SignInRequestBody(val email: String)

@Serializable
private data class SignInConfirmRequestBody(val token: String)

@Serializable
private data class SignInAcceptedBody(val message: String)

/**
 * Issue #296: [publicUrl] is `config.publicUrl` (D57), so the manage
 * view can build the tracker script tag of the keys panel without a
 * second route. A blank value (the local run, or before #295 sets the
 * Fly host) tells the view to build the tag from `location.origin`
 * instead (the correction of the brief review, m16).
 */
@Serializable
private data class MeResponse(val email: String, val publicUrl: String)

/**
 * The sign-in routes of D52, issue #301: `POST /api/signin`, `POST
 * /api/signin/confirm`, `POST /api/signout`, and `GET /api/me`. The
 * session check of [installSessionCheck] already covers `/api/me` and
 * `/api/signout`; both handlers read [ApplicationCall.accountId]
 * with no check of their own. `/api/signin` and `/api/signin/confirm`
 * carry no session, by the exception list of that same check.
 */
fun Route.signInRoutes(signInService: SignInService, config: MonitorConfig) {
    post("/api/signin") {
        val body = receiveBodyOrNull<SignInRequestBody>(call)
        if (body == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody(BAD_BODY_MESSAGE))
            return@post
        }
        val clientAddress = clientAddressOf(call, config.clientIpHeader)
        when (val result = signInService.requestSignIn(body.email, clientAddress)) {
            SignInRequestResult.Accepted ->
                call.respond(HttpStatusCode.Accepted, SignInAcceptedBody(CHECK_YOUR_MAIL_MESSAGE))
            is SignInRequestResult.InvalidRequest ->
                call.respond(HttpStatusCode.BadRequest, ErrorBody(result.message))
        }
    }

    post("/api/signin/confirm") {
        val body = receiveBodyOrNull<SignInConfirmRequestBody>(call)
        if (body == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody(BAD_BODY_MESSAGE))
            return@post
        }
        when (val result = signInService.confirm(body.token)) {
            is SignInConfirmResult.Confirmed -> {
                call.response.header(
                    HttpHeaders.SetCookie,
                    sessionCookieHeader(result.sessionToken, SESSION_MAX_AGE_SECONDS, secureCookie(config)),
                )
                call.respond(HttpStatusCode.NoContent)
            }
            SignInConfirmResult.Invalid ->
                call.respond(HttpStatusCode.BadRequest, ErrorBody(INVALID_LINK_MESSAGE))
        }
    }

    post("/api/signout") {
        val sessionToken = call.request.cookies[SESSION_COOKIE_NAME]
        signInService.signOut(sessionToken)
        call.response.header(HttpHeaders.SetCookie, clearSessionCookieHeader(secureCookie(config)))
        call.respond(HttpStatusCode.NoContent)
    }
}

/**
 * `GET /api/me` sits in its own route block: the session check needs no
 * route name of its own, so this stays a plain `get`, added beside
 * [signInRoutes] in `ApiRoutes.kt`.
 */
fun Route.meRoute(signInService: SignInService, config: MonitorConfig) {
    get("/api/me") {
        val email = signInService.accountEmail(call.accountId())
        if (email == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorBody("Sign in first."))
        } else {
            call.respond(MeResponse(email, config.publicUrl))
        }
    }
}

private fun sessionCookieHeader(token: String, maxAgeSeconds: Long, secure: Boolean): String {
    val secureFlag = if (secure) "; Secure" else ""
    return "$SESSION_COOKIE_NAME=$token; HttpOnly; SameSite=Lax; Path=/; Max-Age=$maxAgeSeconds$secureFlag"
}

private fun clearSessionCookieHeader(secure: Boolean): String {
    val secureFlag = if (secure) "; Secure" else ""
    return "$SESSION_COOKIE_NAME=; HttpOnly; SameSite=Lax; Path=/; Max-Age=0$secureFlag"
}

// D52: Secure when the scheme of publicUrl is https; not on a loopback
// bind with http (the local run and the suite run on http://127.0.0.1).
private fun secureCookie(config: MonitorConfig): Boolean =
    config.publicUrl.startsWith("https://", ignoreCase = true)

// D20, brief review m6: the rightmost value of the header list, because
// a client can spoof the left part of a list header. Fly-Client-IP
// holds one value, so this still reads correctly behind Fly.
internal fun clientAddressOf(call: ApplicationCall, clientIpHeader: String): String {
    if (clientIpHeader.isNotBlank()) {
        val headerValue = call.request.header(clientIpHeader)
        if (headerValue != null) {
            val parts = headerValue.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isNotEmpty()) return parts.last()
        }
    }
    return call.request.origin.remoteHost
}

// The same three catches as AppRegistryRoutes.receiveAppRequest: a
// malformed body, a wrong content shape, or a missing body each give
// null, never the default error page (D11, D15: that page can quote
// part of the body, and a sign-in body carries an email address).
private suspend inline fun <reified T : Any> receiveBodyOrNull(call: ApplicationCall): T? =
    try {
        call.receive<T>()
    } catch (malformedBody: BadRequestException) {
        null
    } catch (wrongShape: ContentTransformationException) {
        null
    } catch (malformed: SerializationException) {
        null
    }
