package octometer.demo

import com.mongodb.client.MongoClients
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import octometer.kit.core.ingest.IngestSettings
import octometer.kit.core.path.PathPatternMatcher
import octometer.kit.core.store.EventLogStore
import octometer.kit.ktor.octometerIngestRoute
import octometer.kit.mongo.store.MongoEventLogStore

/**
 * The route pattern list of the demo page (design decision D40). The page
 * has one path, `/`, so the demo app sets the list itself. It does not
 * read `OCTOMETER_PATH_PATTERNS`, because the one page of this app never
 * changes.
 */
private val DEMO_PATH_PATTERNS: PathPatternMatcher = PathPatternMatcher.of(listOf("/"))

/**
 * The ingest settings of the demo app (design decision D19). Anonymous
 * clicks stay off, so a click with no cookie `demo_user` is dropped.
 */
private val DEMO_INGEST_SETTINGS: IngestSettings = IngestSettings(false, DEMO_PATH_PATTERNS)

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
    MongoClients.create(settings.mongoUri).use { client ->
        val database = client.getDatabase(settings.databaseName)
        val store = MongoEventLogStore(database)
        val written = SyntheticClickGenerator.generate(store)
        println(
            "The generator wrote $written events for ${SyntheticClickGenerator.DEMO_USER_IDS.size} user ids.",
        )
    }
}

private fun runServer(settings: DemoSettings) {
    val client = MongoClients.create(settings.mongoUri)
    val database = client.getDatabase(settings.databaseName)
    val store = MongoEventLogStore(database)
    embeddedServer(Netty, host = settings.host, port = settings.port) {
        demoModule(store)
    }.start(wait = true)
}

/**
 * Installs each route of the demo app (issue #14): the static page, the
 * built tracker, and the ingest route of `kit/jvm-ktor` with [store]. The
 * app writes no CORS header and needs no login, the same rule as the
 * ingest route of the kit (design decision D23).
 */
fun Application.demoModule(store: EventLogStore) {
    routing {
        get("/") {
            call.respondText(DemoIndexPage.html, ContentType.Text.Html)
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
        octometerIngestRoute(store = store, settings = DEMO_INGEST_SETTINGS) { call -> demoUserId(call) }
    }
}
