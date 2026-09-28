package octometer.monitor

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import io.ktor.http.ContentType
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
import io.ktor.server.response.respondText
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
import octometer.monitor.config.requireHostedSecrets
import octometer.monitor.frontend.defaultStaticDir
import octometer.monitor.log.logsDir
import octometer.monitor.frontend.staticFrontend
import octometer.monitor.lock.InstanceLock
import octometer.monitor.lock.InstanceLockFailedException
import octometer.monitor.security.installRequestGuard
import octometer.monitor.security.requireLoopbackBindAddress
import octometer.monitor.store.CorruptDatabaseException
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.system.exitProcess

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
// databaseBytes and freeDiskBytes are decision 5 of issue #58: the
// database file size (octometer.db plus octometer.db-wal, when that
// file exists) and the free space of the disk that holds dataDir.
// storeState, storageSizeBytes, writeStopped, clusterCheckState, and
// lastPurgeAt are the D56 fields of issue #295 (brief review M4).
// storageSizeBytes, writeStopped, and clusterCheckState stay null until
// #297 fills them; lastPurgeAt stays null until the purge job reports
// its own last run time. This route holds no data directory and no
// file path (M4).
@Serializable
data class HealthResponse(
    val version: String,
    val mode: String,
    val refreshSeconds: Int,
    val retentionDays: Int,
    val databaseBytes: Long,
    val freeDiskBytes: Long,
    val storeState: String,
    val storageSizeBytes: Long? = null,
    val writeStopped: Boolean? = null,
    val clusterCheckState: String? = null,
    val lastPurgeAt: String? = null,
)

// The version stays the same for the life of the process, so the route
// reads the packaged resource one time, at the class load, not on each call.
private val VERSION: String = readVersion()

private val log = LoggerFactory.getLogger("octometer.monitor.Application")

fun main(args: Array<String>) {
    // Issue #57, MAJOR 1 of the JVM operations review of pull request
    // #231: logback.xml holds the two console appenders only. It
    // loads no file appender at the class load of this file, before
    // this function starts. The ERROR line of the catch block right
    // below therefore reaches the console alone. configureLogDir
    // loads a second Logback file with the file appender, once
    // dataDir is known.
    val resolved = try {
        loadConfig(args = args)
    } catch (invalidConfig: InvalidConfigException) {
        log.error("The config is invalid. {}", invalidConfig.message)
        exitProcess(2)
    }
    configureLogDir(resolved.config.dataDir)
    // Step 6 of issue #5, extended by D52 (issue #295): refuse a bind
    // address other than 127.0.0.1 or 0.0.0.0, on the configured value
    // that embeddedServer then uses.
    val host = try {
        requireLoopbackBindAddress(resolved.config.bind)
    } catch (invalidBindAddress: InvalidConfigException) {
        log.error("The bind address is invalid. {}", invalidBindAddress.message)
        exitProcess(2)
    }
    // Issue #295, step 4: a public bind in prod mode needs the cluster
    // URI and the admin token, each as a clear start error.
    try {
        requireHostedSecrets(resolved.config)
    } catch (invalidConfig: InvalidConfigException) {
        log.error("The config is invalid. {}", invalidConfig.message)
        exitProcess(2)
    }
    // Decision 2 of issue #58: main creates dataDir and takes the lock
    // right after loadConfig, and before MonitorServices.open runs (the
    // note of 2026-09-22: open() runs the orphan-secret sweep, and two
    // processes must never both write the secret file). Only
    // SqliteDatabase.open made dataDir before this; validateDataDir
    // checks the text of the config value only.
    //
    // Correction round 1, MAJOR A: the old code dropped the result of
    // mkdirs(). A dataDir on a drive that does not exist then reached
    // InstanceLock.acquire, which threw an uncaught NoSuchFileException
    // with the full path. This check gives one ERROR line and the exit
    // code 2 instead, the same as each other invalid-start path.
    val dataDirFile = File(resolved.config.dataDir)
    if (!dataDirFile.mkdirs() && !dataDirFile.isDirectory) {
        log.error("The data folder is not available.")
        exitProcess(2)
    }
    val instanceLock = try {
        InstanceLock.acquire(resolved.config.dataDir)
    } catch (lockFailure: InstanceLockFailedException) {
        log.error(lockFailure.message ?: "The instance lock failed.")
        exitProcess(2)
    }
    Runtime.getRuntime().addShutdownHook(Thread { instanceLock.close() })
    logStart(resolved)
    // Decision 1 of issue #58: a corrupt database fails the quick_check
    // of SqliteDatabase.open, inside MonitorServices.open, inside
    // module(). The catch sits here, around start(wait = true), never
    // inside module() itself: a caught exitProcess call from inside
    // Ktor's own startup call stack deadlocks against
    // io.ktor.server.engine.EmbeddedServer's own JVM shutdown hook,
    // which waits for the same startup call to unwind before it can
    // take the write lock of its own stop() call. Letting
    // CorruptDatabaseException propagate out of module(), and catching
    // it here, lets start() unwind first and release that lock.
    try {
        embeddedServer(
            factory = Netty,
            host = host,
            port = resolved.config.port,
            module = { module(resolved.config) },
        ).start(wait = true)
    } catch (corrupt: CorruptDatabaseException) {
        // Correction round 1 (MAJOR B, the ERROR-sentence MINOR of both
        // reviews): the message already names its own route (the driver
        // check or the quick_check route); this line adds no fixed
        // prefix of its own, so it never mislabels the driver route.
        log.error(corrupt.message ?: "The database file is corrupt.")
        exitProcess(2)
    }
}

fun Application.module(
    config: MonitorConfig,
    staticDir: File? = defaultStaticDir(),
    env: Map<String, String> = System.getenv(),
) {
    // Step 6 of issue #15: this is the first user of the store of #9, thus
    // this issue owns the open call and the close call. MonitorServices
    // opens the store, the secret store, and runs the orphan-secret sweep
    // one time, at the start; it closes when the application stops.
    //
    // Decision 1 of issue #58: a corrupt database throws
    // CorruptDatabaseException here. This function catches nothing; the
    // throw propagates out of start(wait = true), to the catch in main
    // (see the comment there for the reason).
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
    installRequestGuard(config, env)
    routing {
        // D56: the process-and-store check. It skips the Host check
        // (RequestGuard.isHealthzPath) and the admin guard (it sits
        // outside "/api/"), so a load balancer with no header of its
        // own can still reach it. It runs no cluster call.
        get("/healthz") {
            if (storeIsHealthy(services)) {
                call.respondText("ok", ContentType.Text.Plain, HttpStatusCode.OK)
            } else {
                call.respondText("", ContentType.Text.Plain, HttpStatusCode.ServiceUnavailable)
            }
        }
        get("/api/health") {
            call.respond(
                HealthResponse(
                    version = VERSION,
                    mode = config.mode,
                    refreshSeconds = config.pollIntervalSeconds,
                    retentionDays = config.retentionDays,
                    databaseBytes = databaseBytes(config.dataDir),
                    freeDiskBytes = freeDiskBytes(config.dataDir),
                    storeState = if (storeIsHealthy(services)) "ok" else "unhealthy",
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

/**
 * Adds the rolling file appender at the real logs folder (issue #57,
 * D3, D34). It sets the system property `OCTOMETER_LOG_DIR` from
 * [dataDir]. It then resets the shared `LoggerContext`, and runs
 * [JoranConfigurator] on `logback-file.xml`. That file includes
 * `logback.xml`, then adds the file appender.
 *
 * MAJOR 1 of the JVM operations review of pull request #231:
 * `logback.xml` alone never loads a file appender. A file appender
 * of the first config pass, before `dataDir` is known, would open a
 * file in the working folder of the process on each start.
 */
private fun configureLogDir(dataDir: String) {
    System.setProperty("OCTOMETER_LOG_DIR", logsDir(dataDir).absolutePath)
    val context = LoggerFactory.getILoggerFactory() as LoggerContext
    context.reset()
    val configurator = JoranConfigurator()
    configurator.context = context
    val resource = Thread.currentThread().contextClassLoader.getResource("logback-file.xml")
        ?: error("The file logback-file.xml is missing from the classpath.")
    configurator.doConfigure(resource)
}

// D56: the process runs (this code runs at all) and the store answers
// one statement. No cluster call. A failure of the read gives false,
// with one WARN line that names the exception class only.
private suspend fun storeIsHealthy(services: MonitorServices): Boolean =
    try {
        services.database.read { connection -> connection.createStatement().use { it.execute("SELECT 1") } }
        true
    } catch (failure: Exception) {
        log.warn("The store health check failed. {}", failure.javaClass.simpleName)
        false
    }

// Issue #295, brief review B1: internal, not private, so
// ConfigLoaderTest (a different package of the same module) can prove
// the sentinel rule for each secret key, through LogCapture.kt, with
// no container.
internal fun logStart(resolved: ResolvedConfig) {
    for (value in resolved.values) {
        log.info("{} = {} ({})", value.key, escapeForLog(value.value), value.source.label)
    }
    for (warning in resolved.warnings) {
        log.warn(escapeForLog(warning))
    }
}

// Decision 5 of issue #58: octometer.db plus octometer.db-wal, when the
// wal file exists (the store keeps journal_mode=WAL, D3).
private fun databaseBytes(dataDir: String): Long {
    val databaseFile = File(dataDir, "octometer.db")
    val walFile = File(dataDir, "octometer.db-wal")
    val databaseSize = if (databaseFile.isFile) Files.size(databaseFile.toPath()) else 0L
    val walSize = if (walFile.isFile) Files.size(walFile.toPath()) else 0L
    return databaseSize + walSize
}

// Decision 5 of issue #58: the free space of the disk that holds
// dataDir, so the operator sees a full disk before the store does.
private fun freeDiskBytes(dataDir: String): Long =
    Files.getFileStore(File(dataDir).toPath()).usableSpace

private fun readVersion(): String {
    val properties = Properties()
    val stream = HealthResponse::class.java.getResourceAsStream("/version.properties")
    stream?.use { properties.load(it) }
    return properties.getProperty("version", "unknown")
}
