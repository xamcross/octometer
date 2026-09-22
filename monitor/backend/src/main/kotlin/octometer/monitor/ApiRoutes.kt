package octometer.monitor

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import octometer.monitor.apps.appTotals
import octometer.monitor.elements.elementsRoute
import octometer.monitor.erasure.userErasureRoutes
import octometer.monitor.registry.appRegistryRoutes
import octometer.monitor.users.userTotalsRoute

private const val UNKNOWN_API_ROUTE_MESSAGE = "The route does not exist."

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
    appRegistryRoutes(services.appRegistryService)
    appTotals(services.database)
    userTotalsRoute(services.database)
    elementsRoute(services.database)
    userErasureRoutes(services.database)
    // Issue #38, step 4: a request below "/api/" never gets index.html.
    // Ktor tries a constant path segment before this wildcard segment,
    // so this route matches only after each specific "/api/" route
    // above fails to match. It gives the 404 of the API instead.
    get("/api/{path...}") {
        call.respond(HttpStatusCode.NotFound, ErrorBody(UNKNOWN_API_ROUTE_MESSAGE))
    }
}
