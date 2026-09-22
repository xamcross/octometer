package octometer.demo

import io.ktor.server.application.ApplicationCall

/** The maximum length of a valid `demo_user` cookie value (security review MAJOR 1). */
private const val MAX_USER_ID_LENGTH = 254

/**
 * Reads the cookie `demo_user` as the user id (issue #14, step 3). A
 * request with no cookie gives no user id. The ingest route then drops
 * the click, because design decision D19 needs a user id for a stored
 * click.
 *
 * This function also rejects a value with no length, a value above 254
 * characters, and a value with a control character (security review
 * MAJOR 1). The kit treats a bad user id as a defect of the app and
 * answers 500. A rejected value gives `null`, the same as no cookie, so
 * a crafted cookie can never trigger a 500 answer.
 */
fun demoUserId(call: ApplicationCall): String? {
    val value = call.request.cookies["demo_user"] ?: return null
    if (value.isEmpty() || value.length > MAX_USER_ID_LENGTH) return null
    if (value.any { it < ' ' || it == '\u007f' }) return null
    return value
}
