package octometer.monitor.frontend

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import octometer.monitor.allowedHost
import octometer.monitor.module
import octometer.monitor.prodConfig
import octometer.monitor.registerTempRoot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Issue #38: the Angular build sits behind the Host check of D12, the
 * root path and each deep link give `index.html`, a hashed file gets a
 * long cache time, and a path below `/api/` never gives `index.html`.
 */
class StaticFrontendTest {

    private val indexBody = "<html><body>octometer</body></html>"
    private val hashedAssetBody = "console.log('octometer');"

    private fun staticDirWithFrontend(): File {
        val root = Files.createTempDirectory("octometer-static-test-").toFile().also { registerTempRoot(it) }
        File(root, "index.html").writeText(indexBody)
        File(root, "main-GES6WX3U.js").writeText(hashedAssetBody)
        return root
    }

    @Test
    fun `the root path serves index html with a no-cache header and no CORS header`() = testApplication {
        application { module(prodConfig(), staticDirWithFrontend()) }

        val response = client.get("/") { allowedHost() }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(indexBody, response.bodyAsText())
        assertEquals("no-cache", response.headers[HttpHeaders.CacheControl])
        assertNull(response.headers["Access-Control-Allow-Origin"])
    }

    @Test
    fun `a deep link such as apps 1 users serves index html too`() = testApplication {
        application { module(prodConfig(), staticDirWithFrontend()) }

        val response = client.get("/apps/1/users") { allowedHost() }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(indexBody, response.bodyAsText())
        assertEquals("no-cache", response.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `a hashed file name gets a long Cache-Control`() = testApplication {
        application { module(prodConfig(), staticDirWithFrontend()) }

        val response = client.get("/main-GES6WX3U.js") { allowedHost() }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(hashedAssetBody, response.bodyAsText())
        assertEquals("public, max-age=31536000, immutable", response.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `the Host check covers the static files, a wrong Host gets 403 on the root path`() = testApplication {
        application { module(prodConfig(), staticDirWithFrontend()) }

        val response = client.get("/") { header(HttpHeaders.Host, "evil.example") }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `an unknown api path gives the 404 of the api, never index html`() = testApplication {
        application { module(prodConfig(), staticDirWithFrontend()) }

        val response = client.get("/api/does-not-exist") { allowedHost() }

        assertEquals(HttpStatusCode.NotFound, response.status)
        val bodyText = response.bodyAsText()
        assertNotNull(Json.parseToJsonElement(bodyText).jsonObject["error"])
        assertEquals(false, bodyText.contains("octometer"))
    }

    @Test
    fun `with no static folder configured, the root path gives 404 and not index html`() = testApplication {
        application { module(prodConfig(), staticDir = null) }

        val response = client.get("/") { allowedHost() }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
