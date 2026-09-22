package octometer.demo

import io.ktor.server.netty.NettyApplicationEngine
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests of the engine and client settings of the demo app (security
 * review MAJOR 4). Each test reads a value back from a settings object;
 * it opens no real connection and starts no real server.
 */
class ApplicationSettingsTest {

    @Test
    fun `the Netty engine gets a request read timeout of 10 seconds`() {
        val configuration = NettyApplicationEngine.Configuration()

        configureNettyEngine(configuration)

        assertEquals(10, configuration.requestReadTimeoutSeconds)
    }

    @Test
    fun `the MongoDB client gets a socket read timeout of 5 seconds`() {
        val settings = demoMongoClientSettings("mongodb://127.0.0.1:27017")

        val readTimeoutSeconds = settings.socketSettings.getReadTimeout(TimeUnit.SECONDS)

        assertEquals(5, readTimeoutSeconds)
    }
}
