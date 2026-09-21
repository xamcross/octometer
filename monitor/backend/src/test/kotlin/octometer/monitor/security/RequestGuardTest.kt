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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * D12 of the design: the Host check, the Origin check, the content type
 * check, and the security response headers. Each test writes the Host
 * header by itself, because the test client sends no default value.
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
        application { module(prodConfig()) }

        val response = client.get("/api/health") { allowedHost() }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `the Host 127-0-0-1 with the configured port passes`() = testApplication {
        application { module(prodConfig()) }

        val response = client.get("/api/health") { header(HttpHeaders.Host, "127.0.0.1:7431") }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `an upper-case Host passes, because a host name is case-insensitive`() = testApplication {
        application { module(prodConfig()) }

        val response = client.get("/api/health") { header(HttpHeaders.Host, "LOCALHOST:7431") }

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
        application { module(prodConfig()) }

        val response = client.get("/api/no-such-route") { header(HttpHeaders.Host, "evil.example") }
        val allowed = client.get("/api/no-such-route") { allowedHost() }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(HttpStatusCode.NotFound, allowed.status)
        assertEquals("nosniff", allowed.headers["X-Content-Type-Options"])
    }

    @Test
    fun `a route added only in this test still gets the Host check, with no new code at the route`() =
        testApplication {
            application {
                module(prodConfig())
                routing {
                    routeGet("/api/added-by-test") { call.respond(HttpStatusCode.OK) }
                }
            }

            val blocked = client.get("/api/added-by-test") { header(HttpHeaders.Host, "evil.example") }
            val allowed = client.get("/api/added-by-test") { allowedHost() }

            assertEquals(HttpStatusCode.Forbidden, blocked.status)
            assertEquals(HttpStatusCode.OK, allowed.status)
        }

    @Test
    fun `a Host on the bare default port 80 passes, because a browser omits that port`() = testApplication {
        val port80Config = MonitorConfig(
            mode = "prod",
            port = 80,
            dataDir = "C:/data",
            settleLagSeconds = 60,
            retentionDays = 395,
        )
        application { module(port80Config) }

        val withPort = client.get("/api/health") { header(HttpHeaders.Host, "localhost:80") }
        val withoutPort = client.get("/api/health") { header(HttpHeaders.Host, "localhost") }

        assertEquals(HttpStatusCode.OK, withPort.status)
        assertEquals(HttpStatusCode.OK, withoutPort.status)
    }

    // -- The Origin check, as an allow-list of the safe methods -----------

    @Test
    fun `an Origin on the bare default port 80 passes, because a browser omits that port`() = testApplication {
        val port80Config = MonitorConfig(
            mode = "prod",
            port = 80,
            dataDir = "C:/data",
            settleLagSeconds = 60,
            retentionDays = 395,
        )
        application {
            module(port80Config)
            routing {
                routePost("/api/added-by-test") { call.respond(HttpStatusCode.NoContent) }
            }
        }

        val response = client.post("/api/added-by-test") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.Json)
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
        application {
            module(prodConfig())
            routing {
                routePost("/api/added-by-test") { call.respond(HttpStatusCode.NoContent) }
            }
        }

        val response = client.post("/api/added-by-test") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431")
            contentType(ContentType.Application.Json)
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
        application {
            module(devConfig())
            routing {
                routePost("/api/added-by-test") { call.respond(HttpStatusCode.NoContent) }
            }
        }

        val response = client.post("/api/added-by-test") {
            header(HttpHeaders.Host, "localhost:4200")
            header(HttpHeaders.Origin, "http://localhost:4200")
            contentType(ContentType.Application.Json)
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
        application {
            module(prodConfig())
            routing {
                routeDelete("/api/added-by-test") {
                    handlerRan = true
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }

        val response = client.delete("/api/added-by-test") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431")
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
}
