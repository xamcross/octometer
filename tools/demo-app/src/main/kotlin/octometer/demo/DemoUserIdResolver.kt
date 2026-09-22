package octometer.demo

import io.ktor.server.application.ApplicationCall

/**
 * Reads the cookie `demo_user` as the user id (issue #14, step 3). A
 * request with no cookie gives no user id. The ingest route then drops
 * the click, because design decision D19 needs a user id for a stored
 * click.
 */
fun demoUserId(call: ApplicationCall): String? = call.request.cookies["demo_user"]
