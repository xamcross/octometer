package octometer.monitor.frontend

import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import octometer.monitor.allowedHost
import octometer.monitor.module
import octometer.monitor.prodConfig
import octometer.monitor.registerTempRoot
import octometer.monitor.sessionCookieValue
import octometer.monitor.withSession
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

    // Correction round 1 of issue #38 (MINOR 2, security review): D12
    // names HEAD a safe method, the same as GET, for the static route
    // too. AutoHeadResponse (Application.kt) builds the answer.
    @Test
    fun `HEAD on the root path answers 200 with no body`() = testApplication {
        application { module(prodConfig(), staticDirWithFrontend()) }

        val response = client.head("/") { allowedHost() }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("", response.bodyAsText())
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
        val config = prodConfig()
        application { module(config, staticDirWithFrontend()) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)

        val response = client.get("/api/does-not-exist") { allowedHost(); withSession(session) }

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

    // Correction round 1 of issue #38 (MAJOR 2, security review; MAJOR 1,
    // release review): the hash of an Angular bundle is base64url, so it
    // can hold "-" and "_". I broke HASHED_FILE_NAME back to the earlier
    // pattern, ran this test, and saw it fail:
    // "expected:<public, max-age=...> but was:<no-cache>". I restored
    // the pattern before this run.
    @Test
    fun `a hashed name with a dash or an underscore in the hash still gets a long Cache-Control`() = testApplication {
        val root = Files.createTempDirectory("octometer-static-test-").toFile().also { registerTempRoot(it) }
        File(root, "index.html").writeText(indexBody)
        File(root, "chunk-C-Ty_1Re.js").writeText(hashedAssetBody)
        File(root, "chunk-D8Q5_m8v.js").writeText(hashedAssetBody)
        application { module(prodConfig(), root) }

        val dashResponse = client.get("/chunk-C-Ty_1Re.js") { allowedHost() }
        val underscoreResponse = client.get("/chunk-D8Q5_m8v.js") { allowedHost() }

        assertEquals("public, max-age=31536000, immutable", dashResponse.headers[HttpHeaders.CacheControl])
        assertEquals("public, max-age=31536000, immutable", underscoreResponse.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `a plain asset name such as favicon ico gets the short Cache-Control, not a long one`() = testApplication {
        val root = Files.createTempDirectory("octometer-static-test-").toFile().also { registerTempRoot(it) }
        File(root, "index.html").writeText(indexBody)
        File(root, "favicon.ico").writeText(hashedAssetBody)
        application { module(prodConfig(), root) }

        val response = client.get("/favicon.ico") { allowedHost() }

        assertEquals("no-cache", response.headers[HttpHeaders.CacheControl])
    }

    // Correction round 2 (MINOR 1, second security review): the earlier
    // pattern matched a plain asset with a dash and 8 characters before
    // its extension. I broke HASHED_FILE_NAME back to that pattern, ran
    // this test, and saw it fail: "expected:<no-cache> but
    // was:<public, max-age=...>". I restored the pattern before this
    // run.
    @Test
    fun `a plain asset name with a dash and 8 characters, such as logo-abcdefgh png, gets the short Cache-Control`() =
        testApplication {
            val root = Files.createTempDirectory("octometer-static-test-").toFile().also { registerTempRoot(it) }
            File(root, "index.html").writeText(indexBody)
            File(root, "logo-abcdefgh.png").writeText(hashedAssetBody)
            application { module(prodConfig(), root) }

            val response = client.get("/logo-abcdefgh.png") { allowedHost() }

            assertEquals("no-cache", response.headers[HttpHeaders.CacheControl])
        }

    // Correction round 2 (MINOR 2, second security review): a request
    // for a hashed name with no matching file used to fall through to
    // index.html with 200. I removed the looksLikeMissingHashedAsset
    // check, ran this test, and saw it fail: "expected:<404> but
    // was:<200>". I restored the check before this run.
    @Test
    fun `a hashed name with no matching file gives 404, not index html`() = testApplication {
        application { module(prodConfig(), staticDirWithFrontend()) }

        val response = client.get("/chunk-ZZZZZZZZ.js") { allowedHost() }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(false, response.bodyAsText().contains("octometer"))
    }

    // Correction round 1 (MINOR 3, security review; MINOR 5, release
    // review): I deleted index.html from respondIndexOrMissing, ran this
    // test with no such check, and saw it fail with 500 (the default
    // Ktor error page of a missing file). The check is back in the file.
    @Test
    fun `with a static folder that holds no index html, the root path gives 404, not 500`() = testApplication {
        val root = Files.createTempDirectory("octometer-static-test-").toFile().also { registerTempRoot(it) }
        application { module(prodConfig(), root) }

        val response = client.get("/") { allowedHost() }

        assertEquals(HttpStatusCode.NotFound, response.status)
        val bodyText = response.bodyAsText()
        assertNotNull(Json.parseToJsonElement(bodyText).jsonObject["error"])
    }

    // Correction round 1 (MINOR 1, security review): a path below "/api/"
    // with a bad percent escape (for example "/%zz") used to reach this
    // route and get index.html with a second Cache-Control header. The
    // static route now checks isApiPath itself, with the same decode
    // rule as RequestGuard, before it looks for a file.
    @Test
    fun `an api path with a bad escape gets the fixed 404 of the api, not index html`() = testApplication {
        val config = prodConfig()
        application { module(config, staticDirWithFrontend()) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)

        val response = client.get("/%61pi/does-not-exist") { allowedHost(); withSession(session) }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(false, response.bodyAsText().contains("octometer"))
        assertEquals(1, response.headers.getAll(HttpHeaders.CacheControl)?.size)
    }

    @Test
    fun `defaultStaticDir gives null in a test run, because the code source is a folder, not a jar`() {
        assertNull(defaultStaticDir())
    }

    // Correction round 1 (corrections item 3): a fake jar proves the
    // sibling-folder rule of D35, with no need to package a real one.
    @Test
    fun `staticDirBesideJar finds the static folder beside a jar file`() {
        val installRoot = Files.createTempDirectory("octometer-install-root-").toFile().also { registerTempRoot(it) }
        val libDir = File(installRoot, "lib").also { it.mkdirs() }
        val jarFile = File(libDir, "backend.jar").also { it.writeText("not a real jar, only its path matters") }
        val staticDir = File(installRoot, "static").also { it.mkdirs() }

        val result = staticDirBesideJar(jarFile)

        assertEquals(staticDir.canonicalFile, result?.canonicalFile)
    }

    @Test
    fun `staticDirBesideJar gives null when the jar has no static sibling folder`() {
        val installRoot = Files.createTempDirectory("octometer-install-root-").toFile().also { registerTempRoot(it) }
        val libDir = File(installRoot, "lib").also { it.mkdirs() }
        val jarFile = File(libDir, "backend.jar").also { it.writeText("no static sibling here") }

        assertNull(staticDirBesideJar(jarFile))
    }

    // Issue #298, D53 (the brief review of 2026-10-01, B5): GET
    // /tracker.js, served from the same static folder as the Angular
    // build. These cases cover the headers, the two cache rules, and
    // the content; octometer.monitor.tracker holds the route itself.

    private val trackerBody = "console.log('octometer tracker');"

    private fun trackerHashOf(body: String): String =
        MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun staticDirWithTracker(body: String = trackerBody): File {
        val root = Files.createTempDirectory("octometer-tracker-test-").toFile().also { registerTempRoot(it) }
        File(root, "tracker.js").writeText(body)
        return root
    }

    @Test
    fun `GET tracker js serves the bundle with its content type and ETag`() = testApplication {
        application { module(prodConfig(), staticDirWithTracker()) }

        val response = client.get("/tracker.js") { allowedHost() }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(trackerBody, response.bodyAsText())
        assertEquals("text/javascript; charset=utf-8", response.headers[HttpHeaders.ContentType])
        assertEquals("\"${trackerHashOf(trackerBody)}\"", response.headers[HttpHeaders.ETag])
    }

    @Test
    fun `GET tracker js with no v query gives the one-hour Cache-Control`() = testApplication {
        application { module(prodConfig(), staticDirWithTracker()) }

        val response = client.get("/tracker.js") { allowedHost() }

        assertEquals("public, max-age=3600", response.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `GET tracker js with the matching v query gives the one-year immutable Cache-Control`() = testApplication {
        application { module(prodConfig(), staticDirWithTracker()) }
        val hash = trackerHashOf(trackerBody)

        val response = client.get("/tracker.js?v=$hash") { allowedHost() }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("public, max-age=31536000, immutable", response.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `GET tracker js with a wrong v query still gives 200 and the one-hour Cache-Control`() = testApplication {
        application { module(prodConfig(), staticDirWithTracker()) }

        val response = client.get("/tracker.js?v=not-the-real-hash") { allowedHost() }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(trackerBody.toByteArray().toList(), response.bodyAsBytes().toList())
        assertEquals("public, max-age=3600", response.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `GET tracker js gives 404 when the static folder holds no tracker bundle`() = testApplication {
        val root = Files.createTempDirectory("octometer-tracker-test-").toFile().also { registerTempRoot(it) }
        application { module(prodConfig(), root) }

        val response = client.get("/tracker.js") { allowedHost() }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertNotNull(Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"])
    }

    @Test
    fun `GET tracker js gives 404 with no static folder configured at all`() = testApplication {
        application { module(prodConfig(), staticDir = null) }

        val response = client.get("/tracker.js") { allowedHost() }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
