package octometer.monitor

import io.ktor.http.HttpStatusCode
import io.ktor.http.URLDecodeException
import io.ktor.http.decodeURLPart
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.serialization.Serializable
import octometer.monitor.config.InvalidConfigException
import octometer.monitor.config.MonitorConfig
import octometer.monitor.config.ResolvedConfig
import octometer.monitor.config.escapeForLog
import octometer.monitor.config.loadConfig
import octometer.monitor.frontend.defaultStaticDir
import octometer.monitor.frontend.staticFrontend
import octometer.monitor.security.installRequestGuard
import octometer.monitor.security.requireLoopbackBindAddress
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Properties
import kotlin.system.exitProcess

private const val HOST = "127.0.0.1"

// The fixed 400 sentence of issue #148. It names no part of the request.
private const val REQUEST_NOT_VALID_MESSAGE = "The request is not valid."

// Issue #31, correction round 1 (BadQueryEscapeTest): a bad percent
// escape in the path must reach no log line, the same rule as a bad
// escape in the query string. CallLogging's format function below logs
// this fixed text in place of the raw path, when the path fails to
// decode.
private const val INVALID_PATH_LOG_TEXT = "<invalid path>"

// refreshSeconds is config.pollIntervalSeconds (issue #17, decision 6).
// One value now sets both the poll rate and the UI refresh rate. An
// earlier form read a separate, duplicate value of Mode; that field is
// gone.
@Serializable
data class HealthResponse(
    val version: String,
    val mode: String,
    val refreshSeconds: Int,
    val retentionDays: Int,
)

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

fun Application.module(config: MonitorConfig, staticDir: File? = defaultStaticDir()) {
    // Step 6 of issue #15: this is the first user of the store of #9, thus
    // this issue owns the open call and the close call. MonitorServices
    // opens the store, the secret store, and runs the orphan-secret sweep
    // one time, at the start; it closes when the application stops.
    val services = MonitorServices.open(config)
    monitor.subscribe(ApplicationStopped) {
        services.close()
    }

    install(ContentNegotiation) {
        json()
    }
    // Issue #31 (D11, D15): one line for each request, with the method
    // and the path only. The format function builds the whole text, so
    // no plugin default can add the query string or a header. Ktor's
    // Routing plugin can still add its own TRACE line with the full
    // path at a raised root level (logback.xml, MAJOR 2 of the review
    // of pull request #170); no route of this application holds a user
    // id, a session id, or a connection string in a path segment (D13),
    // so that TRACE line stays safe. CallLoggingTraceTest proves this
    // plugin's own line holds no query string, at any level, on a real
    // Netty server.
    //
    // Correction round 1 (BadQueryEscapeTest, a pre-existing test): a
    // raw path can hold a bad percent escape, for example "/%zz". This
    // format function must not echo it back. It decodes the path
    // first, the same check as RequestGuard.isApiPath, and it logs the
    // fixed text of INVALID_PATH_LOG_TEXT in place of the raw path when
    // that decode fails.
    install(CallLogging) {
        format { call ->
            val rawPath = call.request.path()
            val loggedPath = try {
                rawPath.decodeURLPart()
                rawPath
            } catch (badEscape: URLDecodeException) {
                INVALID_PATH_LOG_TEXT
            }
            "${call.request.httpMethod.value} $loggedPath"
        }
    }
    // Correction round 1 of issue #38 (MINOR 2, security review): D12
    // names HEAD a safe method, the same as GET. Each GET route must
    // answer HEAD the same way, with no body. This plugin builds the
    // HEAD answer from the GET route, for every route below.
    install(AutoHeadResponse)
    // MAJOR 6 (Ktor review) and MAJOR 2 of the second review: a failure
    // that leaves a route handler must never reach the default Ktor error
    // page. That page can print the request and the stack trace.
    // kotlinx.coroutines.CancellationException is a type alias of
    // java.util.concurrent.CancellationException on the JVM. A task
    // inside a store can throw that exact class while this call stays
    // active. Only a real cancellation of this call may skip the response.
    install(StatusPages) {
        // Issue #148: the URL decoder of Ktor throws BadRequestException
        // for a bad percent escape, in a query string or in a path, for
        // example "?x=%zz". RequestGuard checks only the path, so this
        // exception reaches StatusPages.
        // Correction round 1: Ktor picks the nearest parent class, so
        // this handler wins over the catch-all. The message of
        // BadRequestException can hold the raw request target. The log
        // line names the exception class only, at a level below ERROR.
        // App code must throw a different class for a server failure. A
        // throw of BadRequestException here always means a client
        // mistake.
        exception<BadRequestException> { call, cause ->
            log.info("The request is not valid. {}", cause.javaClass.simpleName)
            call.respond(HttpStatusCode.BadRequest, ErrorBody(REQUEST_NOT_VALID_MESSAGE))
        }
        exception<Throwable> { call, cause ->
            if (cause is CancellationException && !call.isActive) throw cause
            log.error("An unhandled exception reached the server. {}", cause.javaClass.simpleName)
            call.respond(HttpStatusCode.InternalServerError, ErrorBody("The server had an internal error."))
        }
    }
    installRequestGuard(config)
    routing {
        get("/api/health") {
            call.respond(
                HealthResponse(
                    version = VERSION,
                    mode = config.mode,
                    refreshSeconds = config.pollIntervalSeconds,
                    retentionDays = config.retentionDays,
                ),
            )
        }
        apiRoutes(services)
        // Issue #38, step 4: the Angular build, when the distribution
        // has one. A dev-mode run through Gradle has none; ng serve
        // then serves the UI on its own port (D27).
        if (staticDir != null) {
            // Correction round 1 (MINOR 3, security review; MINOR 5,
            // release review): a missing index.html gives 404 for each
            // page, not 500. This warning names the cause once, at the
            // start, so a broken or a partial install is clear at once.
            if (!File(staticDir, "index.html").isFile) {
                log.warn("The static folder holds no index.html. Each page answers 404 until a full install replaces it.")
            }
            staticFrontend(staticDir)
        }
    }
}

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
