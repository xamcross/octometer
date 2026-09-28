package octometer.monitor.accounts

import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey

/** The name of the session cookie (D52, issue #301). */
const val SESSION_COOKIE_NAME = "octometer_session"

/**
 * The account id of the present request (D52, issue #301). The session
 * check interceptor of [installSessionCheck] sets this attribute, once
 * it reads a valid session. Each route handler under `/api/` reads it
 * through [ApplicationCall.accountId], never the store on its own.
 */
val ACCOUNT_ID_KEY: AttributeKey<Long> = AttributeKey("octometer.accountId")

/**
 * The account id of the present request. It throws when the session
 * check did not run, or did not pass; every route below `/api/`, except
 * the two sign-in routes, sits behind that check, so this is safe to
 * call from a route handler.
 */
fun ApplicationCall.accountId(): Long = attributes[ACCOUNT_ID_KEY]
