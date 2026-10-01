package octometer.demo

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.util.concurrent.TimeUnit
import octometer.kit.core.ingest.IngestSettings
import octometer.kit.core.store.EventLogStore
import octometer.kit.ktor.octometerIngestRoute
import octometer.kit.mongo.store.MongoEventLogStore

/**
 * The ingest settings of the demo app (design decision D19). Anonymous
 * clicks stay off, so a click with no cookie `demo_user` is dropped.
 * This app types no route pattern list of its own (design decision D46,
 * issue #275): `octometerIngestRoute` derives it from `demoModule`'s own
 * routes, once its `routing {}` block runs.
 */
private val DEMO_INGEST_SETTINGS: IngestSettings = IngestSettings(false)

/**
 * The request read timeout of the Netty engine, in seconds (security
 * review MAJOR 4). The kit sets no timeout for a slow request body, so
 * the app must set its own timeout.
 */
private const val NETTY_REQUEST_READ_TIMEOUT_SECONDS = 10

/**
 * The socket read timeout of the MongoDB client, in seconds (security
 * review MAJOR 4). The driver sets no read timeout by default, so a
 * blocked primary would hold a store thread for ever.
 */
private const val MONGO_SOCKET_READ_TIMEOUT_SECONDS = 5L

/**
 * Sets the request read timeout of the Netty engine (security review
 * MAJOR 4). A test applies this function to a fresh configuration
 * object, then reads the value back.
 */
internal fun configureNettyEngine(configuration: NettyApplicationEngine.Configuration) {
    configuration.requestReadTimeoutSeconds = NETTY_REQUEST_READ_TIMEOUT_SECONDS
}

/**
 * Builds the [MongoClientSettings] of the demo app from [mongoUri]. It
 * adds a socket read timeout, so a blocked primary cannot hold a store
 * thread for ever (security review MAJOR 4). A test builds an instance
 * and reads the timeout back; the test opens no real connection.
 */
internal fun demoMongoClientSettings(mongoUri: String): MongoClientSettings =
    MongoClientSettings.builder()
        .applyConnectionString(ConnectionString(mongoUri))
        .applyToSocketSettings { it.readTimeout(MONGO_SOCKET_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        .build()

private fun createMongoClient(settings: DemoSettings): MongoClient =
    MongoClients.create(demoMongoClientSettings(settings.mongoUri))

/**
 * Starts the demo app (issue #14). The flag `--generate` writes synthetic
 * clicks through the store, then exits; it starts no server. With no
 * flag, this function starts the Ktor server on a loopback address and a
 * fixed port ([DemoSettings]).
 */
fun main(args: Array<String>) {
    val settings = DemoSettings.fromEnvironment()
    if (args.contains("--generate")) {
        runGenerate(settings)
        return
    }
    runServer(settings)
}

private fun runGenerate(settings: DemoSettings) {
    createMongoClient(settings).use { client ->
        val database = client.getDatabase(settings.databaseName)
        val store = MongoEventLogStore(database)
        val written = SyntheticClickGenerator.generate(store)
        println(
            "The generator wrote $written events for ${SyntheticClickGenerator.DEMO_USER_IDS.size} user ids.",
        )
    }
}

private fun runServer(settings: DemoSettings) {
    val client = createMongoClient(settings)
    val database = client.getDatabase(settings.databaseName)
    val store = MongoEventLogStore(database)
    embeddedServer(
        factory = Netty,
        environment = applicationEnvironment(),
        configure = {
            connector {
                host = settings.host
                port = settings.port
            }
            configureNettyEngine(this)
        },
    ) {
        demoModule(store, settings.collectorUrl, settings.appKey)
        // MINOR 11 of the Ktor review: close the client at the stop
        // event, so a restart of the app leaks no connection.
        monitor.subscribe(ApplicationStopped) {
            client.close()
        }
    }.start(wait = true)
}

/**
 * Installs each route of the demo app (issue #14): the static page, the
 * built tracker, and the ingest route of `kit/jvm-ktor` with [store]. The
 * app writes no CORS header and needs no login, the same rule as the
 * ingest route of the kit (design decision D23).
 *
 * [collectorUrl] and [appKey] are the hosted collector of design
 * decision D50 (issue #297). A blank [collectorUrl] (the default, for
 * each existing caller of this function) keeps the page's own tracker
 * pointed at this app's ingest route, with the kit route below still in
 * place, the behavior before this issue. With [collectorUrl] set, the
 * page sends every click to the hosted collector instead; the kit
 * route below stays installed (an app with the compose file and no
 * suite can still run it directly), but the suite's own page then
 * sends it no event (the maintainer's note of 2026-10-01).
 */
fun Application.demoModule(store: EventLogStore, collectorUrl: String = "", appKey: String = "") {
    routing {
        get("/") {
            call.respondText(DemoIndexPage.html(collectorUrl, appKey), ContentType.Text.Html)
        }
        get("/tracker/{fileName}") {
            val fileName = call.parameters["fileName"]
            val bytes = fileName?.let { TrackerAssets.read(it) }
            if (bytes == null) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                call.respondBytes(bytes, ContentType("text", "javascript"))
            }
        }
        get("/items/{id}") {
            val itemId = call.parameters["id"].orEmpty()
            call.respondText(DemoItemPage.html(itemId, collectorUrl, appKey), ContentType.Text.Html)
        }
        octometerIngestRoute(store = store, settings = DEMO_INGEST_SETTINGS) { call -> demoUserId(call) }
    }
}
