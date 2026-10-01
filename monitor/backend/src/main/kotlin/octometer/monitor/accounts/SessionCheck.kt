package octometer.monitor.accounts

import io.ktor.http.HttpStatusCode
import io.ktor.http.URLDecodeException
import io.ktor.http.decodeURLPart
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.response.respond
import java.time.Clock
import kotlinx.serialization.Serializable
import octometer.monitor.security.isApiPath
import octometer.monitor.store.SqliteDatabase

@Serializable
private data class SessionErrorBody(val error: String)

private const val SIGN_IN_FIRST_MESSAGE = "Sign in first."

// The routes of D52 that need no session (issue #301, brief review B1,
// m4): /healthz sits outside /api/ already. Issue #297 adds the
// dev-only test hook of the end-to-end suite (the brief review of
// 2026-10-01, M5); a browser page with no session must still read it.
private val EXCLUDED_PATHS = setOf("/api/signin", "/api/signin/confirm", "/api/dev/collector-counters")

/**
 * The session check of D52 (issue #301): one interceptor after the
 * guard block of `RequestGuard.kt`. Every route under `/api/`, except
 * [EXCLUDED_PATHS], needs a session; a missing or an expired session
 * answers 401 with `{"error": "Sign in first."}`. A valid session puts
 * the account id into the call attribute [ACCOUNT_ID_KEY]; a route
 * handler reads it through [octometer.monitor.accounts.accountId].
 *
 * The path check mirrors `RequestGuard.isApiPath`: it decodes the raw
 * path first, then it collapses a repeated slash, so a request under
 * `/api/` cannot dodge the check with a bad escape or an extra slash.
 * A bad escape fails closed: it counts as an API path, and the session
 * check still runs.
 */
fun Application.installSessionCheck(database: SqliteDatabase, clock: Clock = Clock.systemUTC()) {
    intercept(ApplicationCallPipeline.Setup) {
        val rawPath = call.request.path()
        if (!isApiPath(rawPath)) return@intercept
        if (normalisedPath(rawPath) in EXCLUDED_PATHS) return@intercept

        val sessionToken = call.request.cookies[SESSION_COOKIE_NAME]
        val accountId = sessionToken?.let { token ->
            val sessionHash = sha256Hex(token)
            database.read { reader -> findValidSessionAccountId(reader, sessionHash, clock.millis()) }
        }
        if (accountId == null) {
            call.respond(HttpStatusCode.Unauthorized, SessionErrorBody(SIGN_IN_FIRST_MESSAGE))
            finish()
            return@intercept
        }
        call.attributes.put(ACCOUNT_ID_KEY, accountId)
    }
}

private fun normalisedPath(rawPath: String): String {
    val decoded = try {
        rawPath.decodeURLPart()
    } catch (badEscape: URLDecodeException) {
        return "/api/"
    }
    return Regex("/+").replace(decoded, "/")
}
