package octometer.monitor

import io.ktor.server.routing.Route
import octometer.monitor.config.MonitorConfig
import octometer.monitor.registry.appRegistryRoutes

/**
 * The one entry point for each API route beyond health (MAJOR 4 of the
 * Ktor review). A later issue (#18, #16, #17, #50, #51) adds one line
 * here, and reaches the store or the registry through [services].
 * `Application.kt` does not change for a new route.
 */
fun Route.apiRoutes(config: MonitorConfig, services: MonitorServices) {
    appRegistryRoutes(services.appRegistryService)
}
