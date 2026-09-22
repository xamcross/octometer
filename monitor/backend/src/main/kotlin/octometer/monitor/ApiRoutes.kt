package octometer.monitor

import io.ktor.server.routing.Route
import octometer.monitor.registry.appRegistryRoutes
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
    appRegistryRoutes(services.appRegistryService)
    userTotalsRoute(services.database)
}
