package octometer.monitor.security

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.delete as routeDelete
import io.ktor.server.routing.get as routeGet
import io.ktor.server.routing.method
import io.ktor.server.routing.options as routeOptions
import io.ktor.server.routing.patch as routePatch
import io.ktor.server.routing.post as routePost
import io.ktor.server.routing.put as routePut
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import octometer.monitor.allowedHost
import octometer.monitor.config.MonitorConfig
import octometer.monitor.devConfig
import octometer.monitor.module
import octometer.monitor.prodConfig
import octometer.monitor.sessionCookieValue
import octometer.monitor.testDataDir
import octometer.monitor.withSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * D12 of the design: the Host check, the Origin check, the content type
 * check, and the security response headers. Each test writes the Host
 * header by itself, because the test client sends no default value.
 *
 * Issue #301, D52: every route under `/api/` now needs a session too.
 * A test that expects the request to reach the route handler adds
 * [withSession] with a cookie from [sessionCookieValue]; a test that
 * expects a rejection from the Host, the Origin, or the content type
 * check needs no session, because that rejection runs, and calls
 * `finish()`, before the session check of the same `Setup` phase.
 */
class RequestGuardTest {

    // -- The Host check --------------------------------------------------

    @Test
    fun `a wrong Host header gets 403 on the health route`() = testApplication {
        application { module(prodConfig()) }

        val response = client.get("/api/health") { header(HttpHeaders.Host, "evil.example") }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `the Host localhost with the configured port passes`() = testApplication {
        val config = prodConfig()
        application { module(config) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)

        val response = client.get("/api/health") { allowedHost(); withSession(session) }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `the Host 127-0-0-1 with the configured port passes`() = testApplication {
        val config = prodConfig()
        application { module(config) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)

        val response = client.get("/api/health") {
            header(HttpHeaders.Host, "127.0.0.1:7431")
            withSession(session)
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `an upper-case Host passes, because a host name is case-insensitive`() = testApplication {
        val config = prodConfig()
        application { module(config) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)

        val response = client.get("/api/health") {
            header(HttpHeaders.Host, "LOCALHOST:7431")
            withSession(session)
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `an absent Host header gets 403`() = testApplication {
        application { module(prodConfig()) }

        val response = client.get("/api/health")

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `an unknown route still gets the Host check, and it still holds the security headers`() = testApplication {
        val config = prodConfig()
        application { module(config) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)

        val response = client.get("/api/no-such-route") { header(HttpHeaders.Host, "evil.example") }
        val allowed = client.get("/api/no-such-route") { allowedHost(); withSession(session) }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(HttpStatusCode.NotFound, allowed.status)
        assertEquals("nosniff", allowed.headers["X-Content-Type-Options"])
    }

    @Test
    fun `a route added only in this test still gets the Host check, with no new code at the route`() =
        testApplication {
            val config = prodConfig()
            application {
                module(config)
                routing {
                    routeGet("/api/added-by-test") { call.respond(HttpStatusCode.OK) }
                }
            }
            startApplication()
            val session = sessionCookieValue(config.dataDir)

            val blocked = client.get("/api/added-by-test") { header(HttpHeaders.Host, "evil.example") }
            val allowed = client.get("/api/added-by-test") { allowedHost(); withSession(session) }

            assertEquals(HttpStatusCode.Forbidden, blocked.status)
            assertEquals(HttpStatusCode.OK, allowed.status)
        }

    // Issue #295, brief review M5: the bare host of publicUrl, and the
    // form with ":443", both pass.
    @Test
    fun `the bare host of publicUrl, and the same host with the port 443, both pass`() = testApplication {
        val config = prodConfig(publicUrl = "https://app.0ct0meter.com")
        application { module(config) }
        startApplication()
        val session = sessionCookieValue(config.dataDir)

        val bare = client.get("/api/health") { header(HttpHeaders.Host, "app.0ct0meter.com"); withSession(session) }
        val withPort = client.get("/api/health") {
            header(HttpHeaders.Host, "app.0ct0meter.com:443")
            withSession(session)
        }

        assertEquals(HttpStatusCode.OK, bare.status)
        assertEquals(HttpStatusCode.OK, withPort.status)
    }

    // Issue #295, brief review B3: /healthz skips the Host check.
    // Issue #301: /healthz sits outside /api/, so it needs no session.
    @Test
    fun `healthz answers 200 with the body ok, with no allowed Host header`() = testApplication {
        application { module(prodConfig()) }

        val response = client.get("/healthz") { header(HttpHeaders.Host, "evil.example") }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
    }

    @Test
    fun `a Host on the bare default port 80 passes, because a browser omits that port`() = testApplication {
        val port80Config = MonitorConfig(
            mode = "prod",
            port = 80,
            dataDir = testDataDir(),
            settleLagSeconds = 60,
            retentionDays = 395,
            pollIntervalSeconds = 60,
        )
        application { module(port80Config) }
        startApplication()
        val session = sessionCookieValue(port80Config.dataDir)

        val withPort = client.get("/api/health") { header(HttpHeaders.Host, "localhost:80"); withSession(session) }
        val withoutPort = client.get("/api/health") { header(HttpHeaders.Host, "localhost"); withSession(session) }

        assertEquals(HttpStatusCode.OK, withPort.status)
        assertEquals(HttpStatusCode.OK, withoutPort.status)
    }

    // -- The Origin check, as an allow-list of the safe methods -----------

    @Test
    fun `an Origin on the bare default port 80 passes, because a browser omits that port`() = testApplication {
        val port80Config = MonitorConfig(
            mode = "prod",
            port = 80,
            dataDir = testDataDir(),
            settleLagSeconds = 60,
            retentionDays = 395,
            pollIntervalSeconds = 60,
        )
        application {
            module(port80Config)
            routing {
                routePost("/api/added-by-test") { call.respond(HttpStatusCode.NoContent) }
            }
        }
        startApplication()
        val session = sessionCookieValue(port80Config.dataDir)

        val response = client.post("/api/added-by-test") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.Json)
            withSession(session)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `a POST without an allowed Origin header gets 403 and changes no data`() = testApplication {
        var mutationCount = 0
        application {
            module(prodConfig())
            routing {
                routePost("/api/added-by-test") {
                    mutationCount += 1
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }

        val response = client.post("/api/added-by-test") {
            allowedHost()
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(0, mutationCount)
    }

    @Test
    fun `a PUT, a PATCH, and a DELETE without an allowed Origin header each get 403`() = testApplication {
        application {
            module(prodConfig())
            routing {
                routePut("/api/added-by-test") { call.respond(HttpStatusCode.NoContent) }
                routePatch("/api/added-by-test") { call.respond(HttpStatusCode.NoContent) }
                routeDelete("/api/added-by-test") { call.respond(HttpStatusCode.NoContent) }
            }
        }

        val putResponse = client.put("/api/added-by-test") {
            allowedHost()
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        val patchResponse = client.patch("/api/added-by-test") {
            allowedHost()
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        val deleteResponse = client.delete("/api/added-by-test") { allowedHost() }

        assertEquals(HttpStatusCode.Forbidden, putResponse.status)
        assertEquals(HttpStatusCode.Forbidden, patchResponse.status)
        assertEquals(HttpStatusCode.Forbidden, deleteResponse.status)
    }

    @Test
    fun `a route on a custom method without an allowed Origin header gets 403`() = testApplication {
        var handlerRan = false
        application {
            module(prodConfig())
            routing {
                route("/api/added-by-test") {
                    method(HttpMethod("FOO")) {
                        handle {
                            handlerRan = true
                            call.respond(HttpStatusCode.NoContent)
                        }
                    }
                }
            }
        }

        val response = client.request("/api/added-by-test") {
            method = HttpMethod("FOO")
            allowedHost()
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(false, handlerRan)
    }

    @Test
    fun `a POST with an allowed Origin header and application-json passes`() = testApplication {
        val config = prodConfig()
        application {
            module(config)
            routing {
                routePost("/api/added-by-test") { call.respond(HttpStatusCode.NoContent) }
            }
        }
        startApplication()
        val session = sessionCookieValue(config.dataDir)

        val response = client.post("/api/added-by-test") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431")
            contentType(ContentType.Application.Json)
            withSession(session)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `an Origin with a trailing slash is not an exact match, thus it gets 403`() = testApplication {
        application {
            module(prodConfig())
            routing {
                routePost("/api/added-by-test") { call.respond(HttpStatusCode.NoContent) }
            }
        }

        val response = client.post("/api/added-by-test") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431/")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `an OPTIONS request gets no Access-Control-Allow-Origin, also with an allowed Origin`() = testApplication {
        application { module(prodConfig()) }

        val response = client.options("/api/health") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431")
        }

        assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun `the ng serve origin passes in dev mode`() = testApplication {
        val config = devConfig()
        application {
            module(config)
            routing {
                routePost("/api/added-by-test") { call.respond(HttpStatusCode.NoContent) }
            }
        }
        startApplication()
        val session = sessionCookieValue(config.dataDir)

        val response = client.post("/api/added-by-test") {
            header(HttpHeaders.Host, "localhost:4200")
            header(HttpHeaders.Origin, "http://localhost:4200")
            contentType(ContentType.Application.Json)
            withSession(session)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `the ng serve origin gets 403 in prod mode`() = testApplication {
        application {
            module(prodConfig())
            routing {
                routePost("/api/added-by-test") { call.respond(HttpStatusCode.NoContent) }
            }
        }

        val response = client.post("/api/added-by-test") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:4200")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // Issue #295, brief review M6: the dashboard origin behind Fly
    // passes, so a POST or a DELETE from the deployed dashboard works.
    @Test
    fun `the origin of publicUrl passes for a POST`() = testApplication {
        val config = prodConfig(publicUrl = "https://app.0ct0meter.com")
        application {
            module(config)
            routing {
                routePost("/api/added-by-test") { call.respond(HttpStatusCode.NoContent) }
            }
        }
        startApplication()
        val session = sessionCookieValue(config.dataDir)

        val response = client.post("/api/added-by-test") {
            header(HttpHeaders.Host, "app.0ct0meter.com")
            header(HttpHeaders.Origin, "https://app.0ct0meter.com")
            contentType(ContentType.Application.Json)
            withSession(session)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    // -- The content type check, only on a request with a body -----------

    @Test
    fun `a POST with Content-Type text-plain gets 415`() = testApplication {
        application {
            module(prodConfig())
            routing {
                routePost("/api/added-by-test") { call.respond(HttpStatusCode.NoContent) }
            }
        }

        val response = client.post("/api/added-by-test") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431")
            contentType(ContentType.Text.Plain)
            setBody("not json")
        }

        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    }

    @Test
    fun `a POST with application-x-www-form-urlencoded gets 415`() = testApplication {
        application {
            module(prodConfig())
            routing {
                routePost("/api/added-by-test") { call.respond(HttpStatusCode.NoContent) }
            }
        }

        val response = client.post("/api/added-by-test") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431")
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("a=b")
        }

        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    }

    // A malformed Content-Type (for example ")(") needs a real socket: the
    // Ktor test client parses and rejects it before the request leaves the
    // client. See RequestGuardRawSocketTest.

    @Test
    fun `a DELETE with an allowed Origin and without a body passes the content type rule`() = testApplication {
        var handlerRan = false
        val config = prodConfig()
        application {
            module(config)
            routing {
                routeDelete("/api/added-by-test") {
                    handlerRan = true
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
        startApplication()
        val session = sessionCookieValue(config.dataDir)

        val response = client.delete("/api/added-by-test") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431")
            withSession(session)
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals(true, handlerRan)
    }

    @Test
    fun `a DELETE with an allowed Origin and a text-plain body gets 415`() = testApplication {
        application {
            module(prodConfig())
            routing {
                routeDelete("/api/added-by-test") { call.respond(HttpStatusCode.NoContent) }
            }
        }

        val response = client.delete("/api/added-by-test") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431")
            contentType(ContentType.Text.Plain)
            setBody("not json")
        }

        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    }

    // -- No CORS header ----------------------------------------------------

    @Test
    fun `no response holds Access-Control-Allow-Origin`() = testApplication {
        application { module(prodConfig()) }

        val response = client.get("/api/health") { allowedHost() }

        assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    // -- The response headers, and the Cache-Control path rule ------------
    //
    // Neither test below asserts the status code: RequestGuard sets each
    // header before the session check ever runs (D52, issue #301), so a
    // 401 answer with no session still carries every header below.

    @Test
    fun `each response holds the CSP and X-Content-Type-Options, and the API response holds Cache-Control no-store`() =
        testApplication {
            application { module(prodConfig()) }

            val response = client.get("/api/health") { allowedHost() }

            assertEquals(
                "default-src 'self'; style-src 'self' 'unsafe-inline'; object-src 'none'; " +
                    "base-uri 'self'; frame-ancestors 'none'",
                response.headers["Content-Security-Policy"],
            )
            assertEquals("nosniff", response.headers["X-Content-Type-Options"])
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        }

    @Test
    fun `a rejected request holds the CSP, X-Content-Type-Options, and Cache-Control too`() = testApplication {
        application { module(prodConfig()) }

        val response = client.get("/api/health") { header(HttpHeaders.Host, "evil.example") }

        assertEquals(
            "default-src 'self'; style-src 'self' 'unsafe-inline'; object-src 'none'; " +
                "base-uri 'self'; frame-ancestors 'none'",
            response.headers["Content-Security-Policy"],
        )
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
    }

    // A repeated slash ("//api/health") and a bad percent escape ("/%zz")
    // need a real socket: the Ktor test client rewrites or refuses each one
    // before the request leaves the client. See RequestGuardRawSocketTest.

    @Test
    fun `a percent-encoded api segment still gets Cache-Control no-store`() = testApplication {
        application { module(prodConfig()) }

        val response = client.get("/%61pi/health") { allowedHost() }

        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `a non-api path gets no Cache-Control header`() = testApplication {
        application {
            module(prodConfig())
            routing {
                routeGet("/apidocs.html") { call.respond(HttpStatusCode.OK) }
            }
        }

        val response = client.get("/apidocs.html") { allowedHost() }

        assertNull(response.headers[HttpHeaders.CacheControl])
    }

    // -- OriginCheck (issue #296, D52) -------------------------------------
    //
    // No route of this story calls OriginCheck.matches (#297's collector
    // is the caller); the brief review of 2026-10-01 (m15) asks for the
    // case list here, so the function has its proof before that caller
    // exists.

    // -- isCollectorPath (issue #297, D50, the brief review of 2026-10-01, B1) --
    //
    // MAJOR 4 of the review of pull request #327: isCollectorPath had no
    // test. /collect and /tracker.js each skip the guard's Origin check
    // and content type check (RequestGuard.kt:108); OPTIONS on each path
    // does too, with the same decode-and-collapse path test as
    // isHealthzPath. /collectx and /api/collect are each a different
    // path, so each one stays inside the guard's own check.

    @Test
    fun `isCollectorPath matches the exact path of collect and of tracker-js`() {
        assertEquals(true, isCollectorPath("/collect"))
        assertEquals(true, isCollectorPath("/tracker.js"))
    }

    @Test
    fun `isCollectorPath matches a repeated slash and a percent-encoded segment, the normalised path`() {
        assertEquals(true, isCollectorPath("//collect"))
        assertEquals(true, isCollectorPath("/%63ollect"))
        assertEquals(true, isCollectorPath("//tracker.js"))
    }

    @Test
    fun `isCollectorPath gives false for a longer or a different path`() {
        assertEquals(false, isCollectorPath("/collectx"))
        assertEquals(false, isCollectorPath("/api/collect"))
        assertEquals(false, isCollectorPath("/tracker.js.map"))
        assertEquals(false, isCollectorPath("/healthz"))
    }

    @Test
    fun `isCollectorPath gives false on a bad escape, the same fail-closed rule as isHealthzPath`() {
        assertEquals(false, isCollectorPath("/%zz"))
    }

    @Test
    fun `a POST to collect with no Origin header still reaches the route, with no 403 of the guard`() = testApplication {
        application {
            module(prodConfig())
            routing {
                routePost("/collect") { call.respond(HttpStatusCode.NoContent) }
            }
        }

        val response = client.post("/collect") { allowedHost() }

        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `an OPTIONS on collect and on tracker-js each skip the guard's Origin check too`() = testApplication {
        application {
            module(prodConfig())
            routing {
                routeOptions("/collect") { call.respond(HttpStatusCode.NoContent) }
                routeOptions("/tracker.js") { call.respond(HttpStatusCode.NoContent) }
            }
        }

        val collectResponse = client.options("/collect") { allowedHost() }
        val trackerResponse = client.options("/tracker.js") { allowedHost() }

        assertEquals(HttpStatusCode.NoContent, collectResponse.status)
        assertEquals(HttpStatusCode.NoContent, trackerResponse.status)
    }

    @Test
    fun `OriginCheck matches the scheme, the host, and the port, each after lower-casing`() {
        val origins = listOf("https://Example.com", "http://localhost:4200")

        assertEquals(true, OriginCheck.matches("https://example.com", origins), "a lower-case match of a stored upper-case host")
        assertEquals(true, OriginCheck.matches("https://EXAMPLE.COM", origins), "an upper-case header still matches")
        assertEquals(true, OriginCheck.matches("http://localhost:4200", origins), "an explicit port matches the same explicit port")
        assertEquals(true, OriginCheck.matches("https://example.com:443", origins), "the implicit port 443 matches an explicit :443")
        assertEquals(false, OriginCheck.matches("http://example.com", origins), "the scheme must match too")
        assertEquals(false, OriginCheck.matches("https://example.com:8443", origins), "a different port never matches")
        assertEquals(false, OriginCheck.matches("https://other.example.com", origins), "a different host never matches")
        assertEquals(false, OriginCheck.matches(null, origins), "an absent header is false")
        assertEquals(false, OriginCheck.matches("not-an-origin", origins), "an unreadable header is false")
    }
}
