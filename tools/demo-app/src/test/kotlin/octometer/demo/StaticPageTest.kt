package octometer.demo

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import octometer.kit.core.store.InMemoryEventLogStore

/**
 * Tests of the static page and the tracker route of the demo app (issue
 * #14, step 4). Each test needs no Docker.
 */
class StaticPageTest {

    @Test
    fun `the index page holds three data-octo elements and a select box`() = testApplication {
        application {
            demoModule(InMemoryEventLogStore())
        }

        val response = client.get("/")
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.contentType()?.match(ContentType.Text.Html) == true)
        assertEquals(3, Regex("data-octo=").findAll(body).count(), "The page must hold three data-octo elements.")
        assertTrue(body.contains("id=\"demo-user\""), "The page must hold the select box demo-user.")
        assertTrue(body.contains("demo_user"), "The page must set the cookie demo_user.")
        assertTrue(body.contains("flushIntervalMs: 1000"), "The tracker must use flushIntervalMs 1000.")
        assertTrue(body.contains("/tracker/index.js"), "The page must load the built tracker.")
        assertTrue(body.contains("routes: ['/']"), "The page must give the route pattern list to the tracker.")
    }

    @Test
    fun `the tracker route serves the built index module`() = testApplication {
        application {
            demoModule(InMemoryEventLogStore())
        }

        val response = client.get("/tracker/index.js")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("createTracker"), "The served file must hold the tracker code.")
    }

    @Test
    fun `the tracker route gives 404 for an unknown file`() = testApplication {
        application {
            demoModule(InMemoryEventLogStore())
        }

        val response = client.get("/tracker/missing.js")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `the file name guard blocks a name with a slash`() {
        // Only a slash or a percent escape can carry a traversal segment
        // out of static/tracker/ (security review MINOR 6). A dot-only
        // name, for example "....js", matches the pattern; it gives no
        // file only because the classpath holds none by that name.
        val namesWithASlash = listOf("../../../logback.xml", "..%2F..%2Fbuild.gradle.kts")
        for (name in namesWithASlash) {
            assertTrue(!TrackerAssets.FILE_NAME_PATTERN.matches(name), "The pattern must block the name $name.")
            assertEquals(null, TrackerAssets.read(name), "read must give null for $name.")
        }
    }

    @Test
    fun `the tracker route gives 404 for a dot-only file name`() = testApplication {
        application {
            demoModule(InMemoryEventLogStore())
        }

        val dotOnlyNames = listOf("....js", "..js")
        for (name in dotOnlyNames) {
            val response = client.get("/tracker/$name")
            assertEquals(HttpStatusCode.NotFound, response.status, "The route must give 404 for $name.")
        }
    }
}
