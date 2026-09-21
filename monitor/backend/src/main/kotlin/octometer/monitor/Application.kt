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
import java.util.Properties

private const val HOST = "127.0.0.1"
private const val PORT = 7431
private const val DEFAULT_MODE = "prod"

// Issue #4 replaces DEFAULT_MODE with the full config load.
@Serializable
data class HealthResponse(val version: String, val mode: String)

fun main() {
    embeddedServer(Netty, host = HOST, port = PORT, module = Application::module).start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json()
    }
    routing {
        get("/api/health") {
            call.respond(HealthResponse(version = readVersion(), mode = readMode()))
        }
    }
}

private fun readMode(): String = System.getProperty("octometer.mode", DEFAULT_MODE)

private fun readVersion(): String {
    val properties = Properties()
    val stream = object {}.javaClass.getResourceAsStream("/version.properties")
    stream?.use { properties.load(it) }
    return properties.getProperty("version", "unknown")
}
