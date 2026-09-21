package octometer.monitor

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import octometer.monitor.config.InvalidConfigException
import octometer.monitor.config.Mode
import octometer.monitor.config.MonitorConfig
import octometer.monitor.config.ResolvedConfig
import octometer.monitor.config.escapeForLog
import octometer.monitor.config.loadConfig
import octometer.monitor.security.installRequestGuard
import octometer.monitor.security.requireLoopbackBindAddress
import org.slf4j.LoggerFactory
import java.util.Properties
import kotlin.system.exitProcess

private const val HOST = "127.0.0.1"

@Serializable
data class HealthResponse(val version: String, val mode: String, val refreshSeconds: Int)

// The version stays the same for the life of the process, so the route
// reads the packaged resource one time, at the class load, not on each call.
private val VERSION: String = readVersion()

private val log = LoggerFactory.getLogger("octometer.monitor.Application")

fun main(args: Array<String>) {
    val resolved = try {
        loadConfig(args = args)
    } catch (invalidConfig: InvalidConfigException) {
        log.error("The config is invalid. {}", invalidConfig.message)
        exitProcess(2)
    }
    // Step 6 of issue #5: refuse a non-loopback bind address. D2 names no
    // bind-address config key, thus HOST is a constant, and this call
    // cannot throw today. The call stays, so a future bind-address key
    // reaches the same check, on the value that embeddedServer then uses.
    val host = try {
        requireLoopbackBindAddress(HOST)
    } catch (invalidBindAddress: InvalidConfigException) {
        log.error("The bind address is invalid. {}", invalidBindAddress.message)
        exitProcess(2)
    }
    logStart(resolved)
    embeddedServer(
        factory = Netty,
        host = host,
        port = resolved.config.port,
        module = { module(resolved.config) },
    ).start(wait = true)
}

fun Application.module(config: MonitorConfig) {
    install(ContentNegotiation) {
        json()
    }
    installRequestGuard(config)
    routing {
        get("/api/health") {
            call.respond(
                HealthResponse(
                    version = VERSION,
                    mode = config.mode,
                    refreshSeconds = refreshSeconds(config.mode),
                ),
            )
        }
    }
}

// R2 of the design: the monitor polls each 5 seconds in dev mode and each
// 1 minute in prod mode. D2 holds no separate key for this value. The
// loader already validates config.mode, thus the mode always matches one
// entry of Mode here.
private fun refreshSeconds(mode: String): Int = Mode.fromValue(mode)!!.refreshSeconds

private fun logStart(resolved: ResolvedConfig) {
    for (value in resolved.values) {
        log.info("{} = {} ({})", value.key, escapeForLog(value.value), value.source.label)
    }
    for (warning in resolved.warnings) {
        log.warn(escapeForLog(warning))
    }
}

private fun readVersion(): String {
    val properties = Properties()
    val stream = HealthResponse::class.java.getResourceAsStream("/version.properties")
    stream?.use { properties.load(it) }
    return properties.getProperty("version", "unknown")
}
