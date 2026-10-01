package octometer.monitor

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import java.sql.Connection
import kotlinx.serialization.Serializable
import octometer.monitor.accounts.accountId
import octometer.monitor.accounts.meRoute
import octometer.monitor.accounts.sha256Hex
import octometer.monitor.accounts.signInRoutes
import octometer.monitor.apps.appTotals
import octometer.monitor.config.Mode
import octometer.monitor.elements.elementsRoute
import octometer.monitor.erasure.userErasureRoutes
import octometer.monitor.pages.firstPagesRoute
import octometer.monitor.registry.appRegistryRoutes
import octometer.monitor.sessions.sessionsRoute
import octometer.monitor.store.SqliteDatabase
import octometer.monitor.users.userTotalsRoute

/**
 * The one entry point for each API route beyond health (MAJOR 4 of the
 * Ktor review). A later issue (#18, #16, #17, #50, #51) adds one line
 * here, and reaches the store or the registry through [services].
 * `Application.kt` does not change for a new route.
 *
 * MINOR 3 (third Ktor review): this function took a `config` parameter
 * that it never read. [MonitorServices] already holds the config, so the
 * parameter is gone.
 *
 * [trackerHash] is the hash of the served tracker bundle (design
 * decision D53, issue #298), or `null` with no bundle on this instance
 * (the brief review of 2026-10-01, B5). `meRoute` passes it through to
 * `GET /api/me`, so the manage view's keys panel can build the hashed
 * script tag with no extra route.
 */
fun Route.apiRoutes(services: MonitorServices, trackerHash: String?) {
    // Issue #301, D52: the sign-in routes carry no session; each other
    // route below needs one, through the session check that Application.kt
    // installs. signInRoutes needs the config for the client address
    // header and the cookie's Secure flag.
    signInRoutes(services.signInService, services.config)
    meRoute(services.signInService, services.config, trackerHash)
    appRegistryRoutes(services.appRegistryService, services.dailyEventCountStore)
    appTotals(services.database)
    userTotalsRoute(services.database)
    elementsRoute(services.database)
    firstPagesRoute(services.database)
    userErasureRoutes(services.database, services.sharedWriteClient)
    sessionsRoute(services.database)
    // Issue #297 (D50, the brief review of 2026-10-01, M5): the
    // end-to-end suite's own test hook, in config.mode == "dev" only.
    // It gives the monitor's own per-key request count, because
    // kit/jvm-core keeps no public getter for one key's own count.
    //
    // MAJOR 8 of the review of pull request #327: this route now sits
    // behind the session check (SessionCheck.kt), and it gives the
    // account filter the same as every other route of this file: a
    // counter of an app that a different account owns never appears.
    // It also gives no client address (design decision D15: the
    // address stays in memory only); addressHash is the SHA-256 hex
    // text of the address, never the address itself, so this route
    // still proves two different addresses got two different
    // counters, with no address disclosed.
    if (services.config.mode == Mode.DEV.value) {
        get("/api/dev/collector-counters") {
            val ownedAppIds = ownedAppIds(services.database, call.accountId())
            val counters = services.collectorRuntime.rateLimiting.requestCountSnapshot()
                .mapNotNull { (key, count) ->
                    val appId = key.substringBefore(':').toLongOrNull() ?: return@mapNotNull null
                    if (appId !in ownedAppIds) return@mapNotNull null
                    CollectorCounterEntry(appId, sha256Hex(key.substringAfter(':')).take(16), count)
                }
            call.respond(HttpStatusCode.OK, CollectorCountersResponse(counters))
        }
    }
    // Issue #38, step 4: a request below "/api/" never gets index.html.
    // Ktor tries a constant path segment before this wildcard segment,
    // so this route matches only after each specific "/api/" route
    // above fails to match. It gives the 404 of the API instead.
    //
    // Correction round 1 (MINOR 4, security review): route(...) with
    // handle answers each method, not GET alone. A POST or a DELETE on
    // an unknown API path must also get the fixed JSON body, not an
    // empty 404 body.
    route("/api/{path...}") {
        handle {
            call.respond(HttpStatusCode.NotFound, ErrorBody(UNKNOWN_API_ROUTE_MESSAGE))
        }
    }
}

/**
 * One row of [CollectorCountersResponse]: the app id, the hashed
 * client address (MAJOR 8 of the review of pull request #327: never
 * the address itself), and the request count.
 */
@Serializable
data class CollectorCounterEntry(val appId: Long, val addressHash: String, val count: Long)

/** The body of `GET /api/dev/collector-counters` (issue #297, the brief review of 2026-10-01, M5). */
@Serializable
data class CollectorCountersResponse(val counters: List<CollectorCounterEntry>)

/** The app ids of [accountId] (MAJOR 8), for the account filter of `GET /api/dev/collector-counters`. */
private suspend fun ownedAppIds(database: SqliteDatabase, accountId: Long): Set<Long> =
    database.read { reader -> readOwnedAppIds(reader, accountId) }

private fun readOwnedAppIds(connection: Connection, accountId: Long): Set<Long> =
    connection.prepareStatement("SELECT id FROM app WHERE account_id = ?").use { select ->
        select.setLong(1, accountId)
        select.executeQuery().use { result ->
            val ids = mutableSetOf<Long>()
            while (result.next()) {
                ids += result.getLong("id")
            }
            ids
        }
    }
