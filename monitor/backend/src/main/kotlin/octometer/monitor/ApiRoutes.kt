package octometer.monitor

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import octometer.monitor.accounts.meRoute
import octometer.monitor.accounts.signInRoutes
import octometer.monitor.apps.appTotals
import octometer.monitor.config.Mode
import octometer.monitor.elements.elementsRoute
import octometer.monitor.erasure.userErasureRoutes
import octometer.monitor.pages.firstPagesRoute
import octometer.monitor.registry.appRegistryRoutes
import octometer.monitor.sessions.sessionsRoute
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
 */
fun Route.apiRoutes(services: MonitorServices) {
    // Issue #301, D52: the sign-in routes carry no session; each other
    // route below needs one, through the session check that Application.kt
    // installs. signInRoutes needs the config for the client address
    // header and the cookie's Secure flag.
    signInRoutes(services.signInService, services.config)
    meRoute(services.signInService, services.config)
    appRegistryRoutes(services.appRegistryService, services.dailyEventCountStore)
    appTotals(services.database)
    userTotalsRoute(services.database)
    elementsRoute(services.database)
    firstPagesRoute(services.database)
    userErasureRoutes(services.database)
    sessionsRoute(services.database)
    // Issue #297 (D50, the brief review of 2026-10-01, M5): the
    // end-to-end suite's own test hook, in config.mode == "dev" only.
    // It gives the monitor's own per-key request count, because
    // kit/jvm-core keeps no public getter for one key's own count.
    if (services.config.mode == Mode.DEV.value) {
        get("/api/dev/collector-counters") {
            val counters = services.collectorRuntime.rateLimiting.requestCountSnapshot()
                .map { (key, count) -> CollectorCounterEntry(key, count) }
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

/** One row of [CollectorCountersResponse]: the rate-limit key, and its request count. */
@Serializable
data class CollectorCounterEntry(val key: String, val count: Long)

/** The body of `GET /api/dev/collector-counters` (issue #297, the brief review of 2026-10-01, M5). */
@Serializable
data class CollectorCountersResponse(val counters: List<CollectorCounterEntry>)
