package octometer.monitor.security

import octometer.monitor.withRawSocketServer
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Three cases that the Ktor test client cannot send: it rewrites a
 * repeated slash and a bad percent escape before the request leaves the
 * client, and it refuses a malformed Content-Type value at build time.
 * Each test here starts a real server on a free loopback port, and it
 * stops the server in a finally block, thus it leaves no background work.
 */
class RequestGuardRawSocketTest {

    @Test
    fun `a repeated slash in the path still gets Cache-Control no-store`() {
        withRawSocketServer { port, send ->
            val response = send(
                "GET //api/health HTTP/1.1\r\n" +
                    "Host: localhost:$port\r\n" +
                    "Connection: close\r\n" +
                    "\r\n",
            )

            assertTrue(response.contains("Cache-Control: no-store"), "no Cache-Control in:\n$response")
        }
    }

    @Test
    fun `a path with a bad percent escape fails closed, thus it still gets Cache-Control no-store`() {
        withRawSocketServer { port, send ->
            val response = send(
                "GET /%zz HTTP/1.1\r\n" +
                    "Host: localhost:$port\r\n" +
                    "Connection: close\r\n" +
                    "\r\n",
            )

            assertTrue(response.contains("Cache-Control: no-store"), "no Cache-Control in:\n$response")
        }
    }

    @Test
    fun `two Host headers never give a passing response`() {
        withRawSocketServer { port, send ->
            val response = send(
                "GET /api/health HTTP/1.1\r\n" +
                    "Host: localhost:$port\r\n" +
                    "Host: evil.example\r\n" +
                    "Connection: close\r\n" +
                    "\r\n",
            )

            assertFalse(response.startsWith("HTTP/1.1 200"), "a request with two Host headers must not pass:\n$response")
        }
    }

    @Test
    fun `a malformed Content-Type gets 415, and the body does not echo the header value`() {
        withRawSocketServer { port, send ->
            val body = "x"
            val response = send(
                "POST /api/health HTTP/1.1\r\n" +
                    "Host: localhost:$port\r\n" +
                    "Origin: http://localhost:$port\r\n" +
                    "Content-Type: )(\r\n" +
                    "Content-Length: ${body.toByteArray(StandardCharsets.US_ASCII).size}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" +
                    body,
            )

            assertTrue(response.startsWith("HTTP/1.1 415"), "expected 415, first line of:\n$response")
            assertFalse(response.contains(")("), "the body must not echo the header value:\n$response")
        }
    }
}
