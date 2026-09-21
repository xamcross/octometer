package octometer.monitor

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import java.io.File
import java.nio.file.Files
import octometer.monitor.config.MonitorConfig

// One shared config pair for the tests of this module. HealthRouteTest and
// RequestGuardTest held their own copy before; this file removes the
// duplication.

/**
 * A fresh temporary folder for one test, never the real data folder.
 * Issue #15: module() opens a real SqliteDatabase in config.dataDir, thus
 * a config for a test must never name a real folder such as "C:/data".
 * The parent of the returned folder is a folder of its own, so a test that
 * also reads the sibling "secrets" folder of D34 never shares it with a
 * different test.
 */
fun testDataDir(): String = File(Files.createTempDirectory("octometer-test-").toFile(), "data").absolutePath

/** The dev mode config of the tests, with the configured port 7431. */
fun devConfig(dataDir: String = testDataDir()) = MonitorConfig(
    mode = "dev",
    port = 7431,
    dataDir = dataDir,
    settleLagSeconds = 2,
    retentionDays = 395,
)

/** The prod mode config of the tests, with the configured port 7431. */
fun prodConfig(dataDir: String = testDataDir()) = MonitorConfig(
    mode = "prod",
    port = 7431,
    dataDir = dataDir,
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
