package octometer.monitor

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import octometer.monitor.config.MonitorConfig

// One shared config pair for the tests of this module. HealthRouteTest and
// RequestGuardTest held their own copy before; this file removes the
// duplication.

/** The dev mode config of the tests, with the configured port 7431. */
fun devConfig() = MonitorConfig(
    mode = "dev",
    port = 7431,
    dataDir = "build/dev-data",
    settleLagSeconds = 2,
    retentionDays = 395,
)

/** The prod mode config of the tests, with the configured port 7431. */
fun prodConfig() = MonitorConfig(
    mode = "prod",
    port = 7431,
    dataDir = "C:/data",
    settleLagSeconds = 60,
    retentionDays = 395,
)

/**
 * Sends the Host header that devConfig and prodConfig allow. Each test
 * needs it, because the test client sends no Host header by itself.
 */
fun HttpRequestBuilder.allowedHost() {
    header(HttpHeaders.Host, "localhost:7431")
}
