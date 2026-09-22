package octometer.kit.ktor

import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import java.net.Socket
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.measureTimeMillis
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import octometer.kit.core.ingest.IngestEvent
import octometer.kit.core.ingest.IngestException
import octometer.kit.core.ingest.IngestSettings
import octometer.kit.core.store.DeletionResult
import octometer.kit.core.store.EventLogStore
import octometer.kit.core.store.InMemoryEventLogStore
import org.slf4j.LoggerFactory

/**
 * Tests of the ingest route against design section 4.2 and contract rules
 * C12 to C19, C32, C33, C36, and C37.
 */
class IngestRouteTest {

    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-09-21T10:00:00Z"), ZoneOffset.UTC)

    private val validBody = """{"sessionId":"0b0e4e0e-6a55-4c1e-9a53-0c1f6f7a2d11",""" +
        """"clicks":[{"element":"checkout.save","ageMs":1200}]}"""

    // The name of the SLF4J logger of `call.application.log` inside
    // `testApplication` (confirmed on 2026-09-21 by printing
    // `call.application.log.name` from inside the route).
    private val applicationLoggerName = "io.ktor.test"
    private lateinit var logAppender: CapturingAppender

    @BeforeTest
    fun attachLogAppender() {
        logAppender = CapturingAppender()
        logAppender.start()
        (LoggerFactory.getLogger(applicationLoggerName) as LogbackLogger).addAppender(logAppender)
    }

    @AfterTest
    fun detachLogAppender() {
        (LoggerFactory.getLogger(applicationLoggerName) as LogbackLogger).detachAppender(logAppender)
        logAppender.stop()
    }

    @Test
    fun `a valid batch gives 204 and stores the events`() = testApplication {
        val store = InMemoryEventLogStore()
        application {
            routing {
                octometerIngestRoute(store = store, settings = IngestSettings(true)) { "user-1" }
            }
        }

        val response = client.post(DEFAULT_INGEST_PATH) {
            contentType(ContentType.Application.Json)
            setBody(validBody)
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        val stored = store.events()
        assertEquals(1, stored.size)
        assertEquals("checkout.save", stored[0].element())
        assertEquals("user-1", stored[0].userId())
    }

    @Test
    fun `application json with a charset parameter passes`() = testApplication {
        val store = InMemoryEventLogStore()
        application {
            routing {
                octometerIngestRoute(store = store, settings = IngestSettings(true)) { "user-1" }
            }
        }

        val response = client.post(DEFAULT_INGEST_PATH) {
            header(HttpHeaders.ContentType, "application/json; charset=utf-8")
            setBody(validBody)
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `text plain gives 415`() = testApplication {
        val store = InMemoryEventLogStore()
        application {
            routing {
                octometerIngestRoute(store = store) { null }
            }
        }

        val response = client.post(DEFAULT_INGEST_PATH) {
            contentType(ContentType.Text.Plain)
            setBody(validBody)
        }

        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
        assertTrue(store.events().isEmpty())
    }

    @Test
    fun `an absent content type gives 415`() = testApplication {
        val store = InMemoryEventLogStore()
        application {
            routing {
                octometerIngestRoute(store = store) { null }
            }
        }

        val response = client.post(DEFAULT_INGEST_PATH) {
            setBody(validBody)
        }

        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    }

    // The default Ktor test client parses the Content-Type header itself
    // (through the HttpPlainText client plugin) and refuses to send a
    // malformed value at all. A malformed value thus needs a real embedded
    // server and a raw socket, the same method as the security review of
    // this pull request.

    @Test
    fun `a malformed Content-Type value gives 415 with an empty body`() {
        val rawResponse = postRawRequestToRealServer(contentTypeHeaderValue = "@@@")
        assertTrue(rawResponse.startsWith("HTTP/1.1 415"), "Expected 415, got the answer:\n$rawResponse")
        assertTrue(rawResponseBody(rawResponse).isEmpty())
        assertFalse(rawResponse.contains("@@@"))
    }

    @Test
    fun `a Content-Type value with no slash gives 415 with an empty body`() {
        val rawResponse = postRawRequestToRealServer(contentTypeHeaderValue = "json")
        assertTrue(rawResponse.startsWith("HTTP/1.1 415"), "Expected 415, got the answer:\n$rawResponse")
        assertTrue(rawResponseBody(rawResponse).isEmpty())
    }

    /**
     * Starts one real embedded Netty server on an ephemeral port, sends one
     * raw HTTP/1.1 request with the given `Content-Type` header value over a
     * plain socket, then stops the server. Returns the full raw response
     * text (the status line, the headers, and the body).
     */
    private fun postRawRequestToRealServer(contentTypeHeaderValue: String): String {
        val store = InMemoryEventLogStore()
        val server = embeddedServer(Netty, port = 0) {
            routing {
                octometerIngestRoute(store = store) { null }
            }
        }
        server.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors() }
                .first()
                .port
            Socket("127.0.0.1", port).use { socket ->
                val body = validBody.toByteArray(Charsets.UTF_8)
                val request = "POST $DEFAULT_INGEST_PATH HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Content-Type: $contentTypeHeaderValue\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
                socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
                socket.getOutputStream().write(body)
                socket.getOutputStream().flush()
                socket.soTimeout = 5000
                return socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            }
        } finally {
            server.stop(0, 0)
        }
    }

    private fun rawResponseBody(rawResponse: String): String {
        val separatorIndex = rawResponse.indexOf("\r\n\r\n")
        return if (separatorIndex < 0) "" else rawResponse.substring(separatorIndex + 4)
    }

    @Test
    fun `an invalid body gives 400`() = testApplication {
        val store = InMemoryEventLogStore()
        application {
            routing {
                octometerIngestRoute(store = store) { "user-1" }
            }
        }

        val response = client.post(DEFAULT_INGEST_PATH) {
            contentType(ContentType.Application.Json)
            setBody("""{"sessionId":"not-a-uuid","clicks":[]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(store.events().isEmpty())
    }

    @Test
    fun `a body of exactly 16384 bytes gives 204`() = testApplication {
        val store = InMemoryEventLogStore()
        application {
            routing {
                octometerIngestRoute(store = store, settings = IngestSettings(true)) { "user-1" }
            }
        }

        val body = exactlySizedBody(16384)
        val response = client.post(DEFAULT_INGEST_PATH) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        assertEquals(16384, body.toByteArray(Charsets.UTF_8).size)
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `a body of exactly 16385 bytes gives 400`() = testApplication {
        val store = InMemoryEventLogStore()
        application {
            routing {
                octometerIngestRoute(store = store, settings = IngestSettings(true)) { "user-1" }
            }
        }

        val body = exactlySizedBody(16385)
        val response = client.post(DEFAULT_INGEST_PATH) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        assertEquals(16385, body.toByteArray(Charsets.UTF_8).size)
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `a body above 16 KB with a correct Content-Length gives 400`() = testApplication {
        val store = InMemoryEventLogStore()
        application {
            routing {
                octometerIngestRoute(store = store) { "user-1" }
            }
        }

        val oversizedBody = oversizedJsonBody()
        val response = client.post(DEFAULT_INGEST_PATH) {
            contentType(ContentType.Application.Json)
            setBody(oversizedBody)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(store.events().isEmpty())
    }

    @Test
    fun `a body above 16 KB with no Content-Length gives 400 and the route reads no unlimited body`() =
        testApplication {
            val store = InMemoryEventLogStore()
            application {
                routing {
                    octometerIngestRoute(store = store) { "user-1" }
                }
            }

            val oversizedBytes = oversizedJsonBody().toByteArray(Charsets.UTF_8)
            val response = client.post(DEFAULT_INGEST_PATH) {
                contentType(ContentType.Application.Json)
                setBody(object : OutgoingContent.WriteChannelContent() {
                    // No declared length, like a chunked request from a client
                    // that lies about, or omits, Content-Length.
                    override val contentLength: Long? = null
                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        channel.writeFully(oversizedBytes)
                    }
                })
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(store.events().isEmpty())
        }

    @Test
    fun `no response holds a CORS header`() = testApplication {
        val store = InMemoryEventLogStore()
        application {
            routing {
                octometerIngestRoute(store = store, settings = IngestSettings(true)) { "user-1" }
            }
        }

        val response = client.post(DEFAULT_INGEST_PATH) {
            contentType(ContentType.Application.Json)
            setBody(validBody)
        }

        assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    // This test reads the toString() text of a kotlinx-coroutines internal
    // class (Ktor review MINOR A). That text is not a public contract, so
    // an upgrade of kotlinx-coroutines (this module pins version 1.11.0 in
    // gradle/libs.versions.toml) can change it and break this test with no
    // real defect in this module. The failure is loud, so this stays as a
    // second check next to the run-time test below.
    @Test
    fun `the default store dispatcher is a limited view of Dispatchers IO`() {
        assertEquals("Dispatchers.IO.limitedParallelism(8)", defaultStoreDispatcher().toString())
    }

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    @Test
    fun `an injected dispatcher gets the store call`() = testApplication {
        val recordedThreadName = AtomicReference<String>()
        val store = object : EventLogStore {
            override fun append(events: List<IngestEvent>, userId: String?) {
                recordedThreadName.set(Thread.currentThread().name)
            }

            override fun deleteByUserId(userId: String): DeletionResult {
                // Not used by this test.
                return DeletionResult(0, 0)
            }
        }
        val testDispatcher = newSingleThreadContext("octo-store-test-thread")
        try {
            application {
                routing {
                    octometerIngestRoute(
                        store = store,
                        settings = IngestSettings(true),
                        clock = fixedClock,
                        storeDispatcher = testDispatcher,
                    ) { "user-1" }
                }
            }

            val response = client.post(DEFAULT_INGEST_PATH) {
                contentType(ContentType.Application.Json)
                setBody(validBody)
            }

            assertEquals(HttpStatusCode.NoContent, response.status)
            // kotlinx-coroutines debug mode appends " @coroutine#N" to the
            // real thread name while a coroutine runs, thus this test checks
            // the prefix and not the exact name.
            val threadName = recordedThreadName.get()
            assertTrue(
                threadName != null && threadName.startsWith("octo-store-test-thread"),
                "Expected the store call on \"octo-store-test-thread\", but it ran on \"$threadName\".",
            )
        } finally {
            testDispatcher.close()
        }
    }

    @Test
    fun `64 slow store calls at the same time leave other Dispatchers IO work free`() = testApplication {
        val store = object : EventLogStore {
            override fun append(events: List<IngestEvent>, userId: String?) {
                Thread.sleep(200)
            }

            override fun deleteByUserId(userId: String): DeletionResult {
                // Not used by this test.
                return DeletionResult(0, 0)
            }
        }
        application {
            routing {
                octometerIngestRoute(store = store, settings = IngestSettings(true)) { "user-1" }
            }
        }

        runBlocking {
            coroutineScope {
                val slowCallsDone = AtomicBoolean(false)
                val slowCalls = List(64) {
                    async {
                        client.post(DEFAULT_INGEST_PATH) {
                            contentType(ContentType.Application.Json)
                            setBody(validBody)
                        }
                    }
                }

                // Give the slow calls time to occupy the limited store dispatcher.
                delay(50)

                // A latch proves that the free work ran, instead of a wall
                // clock limit that can flake on a busy machine (Ktor
                // review MINOR B). The free work reads slowCallsDone
                // before it counts the latch down, so a true value proves
                // that the free work ran while the 64 slow calls still
                // occupied the limited dispatcher.
                val freeWorkLatch = CountDownLatch(1)
                val freeWorkRanWhileSlowCallsStillWaited = AtomicBoolean(false)
                launch {
                    withContext(Dispatchers.IO) {
                        // Quick, unrelated Dispatchers.IO work of the app.
                    }
                    freeWorkRanWhileSlowCallsStillWaited.set(!slowCallsDone.get())
                    freeWorkLatch.countDown()
                }

                // The wait itself runs on Dispatchers.IO, not on this
                // coroutine's own thread, so the blocking await() call
                // never starves the launch above of a thread to run on.
                val freeWorkCompletedInTime = withContext(Dispatchers.IO) {
                    freeWorkLatch.await(5, TimeUnit.SECONDS)
                }
                assertTrue(
                    freeWorkCompletedInTime,
                    "Expected other Dispatchers.IO work to complete within 5 seconds.",
                )
                assertTrue(
                    freeWorkRanWhileSlowCallsStillWaited.get(),
                    "Expected other Dispatchers.IO work to stay free while the 64 slow store " +
                        "calls still ran, but the limited dispatcher blocked it.",
                )

                val totalMillis = measureTimeMillis { slowCalls.awaitAll() }
                slowCallsDone.set(true)
                assertTrue(
                    totalMillis >= 900,
                    "Expected the limited dispatcher to serialize the 64 calls into 8 batches " +
                        "of about 200 ms each, but the total time was only $totalMillis ms.",
                )
            }
        }
    }

    @Test
    fun `resolveUserId runs on the coroutine of the call`() = testApplication {
        val threadLocal = ThreadLocal<String>()
        val recordedValue = AtomicReference<String?>()
        val store = InMemoryEventLogStore()
        val threadLocalPlugin = createApplicationPlugin("ThreadLocalSetterForTest") {
            onCall { threadLocal.set("victim-42") }
        }
        application {
            this.install(threadLocalPlugin)
            routing {
                octometerIngestRoute(store = store, settings = IngestSettings(true)) {
                    val value = threadLocal.get()
                    recordedValue.set(value)
                    value
                }
            }
        }

        val response = client.post(DEFAULT_INGEST_PATH) {
            contentType(ContentType.Application.Json)
            setBody(validBody)
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals("victim-42", recordedValue.get())
        assertEquals("victim-42", store.events()[0].userId())
    }

    @Test
    fun `a store defect gives 500 and never 400`() = testApplication {
        val store = object : EventLogStore {
            override fun append(events: List<IngestEvent>, userId: String?) {
                throw IllegalStateException("SENTINEL-store-host-octo-shard-00 user victim-42")
            }

            override fun deleteByUserId(userId: String): DeletionResult {
                // Not used by this test.
                return DeletionResult(0, 0)
            }
        }
        application {
            routing {
                octometerIngestRoute(store = store, settings = IngestSettings(true)) { "user-1" }
            }
        }

        val response = client.post(DEFAULT_INGEST_PATH) {
            contentType(ContentType.Application.Json)
            setBody(validBody)
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("", response.bodyAsText())
        val messages = logAppender.events.map { it.formattedMessage }
        assertTrue(messages.none { it.contains("SENTINEL-store-host-octo-shard-00") })
        assertTrue(messages.none { it.contains("victim-42") })
        assertTrue(messages.any { it.contains(IllegalStateException::class.java.name) })
    }

    @Test
    fun `a store defect that throws an IngestException gives 500 and never 400`() = testApplication {
        val store = object : EventLogStore {
            override fun append(events: List<IngestEvent>, userId: String?) {
                throw IngestException(IngestException.Reason.INVALID_JSON, "the store throws this by mistake")
            }

            override fun deleteByUserId(userId: String): DeletionResult {
                // Not used by this test.
                return DeletionResult(0, 0)
            }
        }
        application {
            routing {
                octometerIngestRoute(store = store, settings = IngestSettings(true)) { "user-1" }
            }
        }

        val response = client.post(DEFAULT_INGEST_PATH) {
            contentType(ContentType.Application.Json)
            setBody(validBody)
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
    }

    // The four tests below check the MAJOR 1 finding of the second security
    // review: a CancellationException of the store or of resolveUserId
    // must never reach the Ktor engine, because the engine then writes the
    // exception message into the answer, or maps a withTimeout into a 504
    // answer, and the route log holds no line at all.

    @Test
    fun `a store that throws CancellationException gives 500 with an empty body and no log leak`() =
        testApplication {
            val store = object : EventLogStore {
                override fun append(events: List<IngestEvent>, userId: String?) {
                    throw CancellationException("SENTINEL-cancel-marker-host-octo-shard-00")
                }

                override fun deleteByUserId(userId: String): DeletionResult {
                    // Not used by this test.
                    return DeletionResult(0, 0)
                }
            }
            application {
                routing {
                    octometerIngestRoute(store = store, settings = IngestSettings(true)) { "user-1" }
                }
            }

            val response = client.post(DEFAULT_INGEST_PATH) {
                contentType(ContentType.Application.Json)
                setBody(validBody)
            }

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertEquals("", response.bodyAsText())
            val messages = logAppender.events.map { it.formattedMessage }
            assertTrue(messages.none { it.contains("SENTINEL-cancel-marker-host-octo-shard-00") })
            assertTrue(messages.any { it.contains(CancellationException::class.java.name) })
        }

    @Test
    fun `a store that uses withTimeout gives 500 with an empty body, and never 504`() = testApplication {
        val store = object : EventLogStore {
            override fun append(events: List<IngestEvent>, userId: String?) {
                runBlocking {
                    withTimeout(50) {
                        delay(1000)
                    }
                }
            }

            override fun deleteByUserId(userId: String): DeletionResult {
                // Not used by this test.
                return DeletionResult(0, 0)
            }
        }
        application {
            routing {
                octometerIngestRoute(store = store, settings = IngestSettings(true)) { "user-1" }
            }
        }

        val response = client.post(DEFAULT_INGEST_PATH) {
            contentType(ContentType.Application.Json)
            setBody(validBody)
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("", response.bodyAsText())
    }

    @Test
    fun `a store that reads a cancelled Future gives 500 with one log line naming the class`() =
        testApplication {
            val executor = Executors.newSingleThreadExecutor()
            try {
                val store = object : EventLogStore {
                    override fun append(events: List<IngestEvent>, userId: String?) {
                        val future = executor.submit<Unit>(Callable { Thread.sleep(1000) })
                        future.cancel(true)
                        future.get()
                    }

                    override fun deleteByUserId(userId: String): DeletionResult {
                        // Not used by this test.
                        return DeletionResult(0, 0)
                    }
                }
                application {
                    routing {
                        octometerIngestRoute(store = store, settings = IngestSettings(true)) { "user-1" }
                    }
                }

                val response = client.post(DEFAULT_INGEST_PATH) {
                    contentType(ContentType.Application.Json)
                    setBody(validBody)
                }

                assertEquals(HttpStatusCode.InternalServerError, response.status)
                assertEquals("", response.bodyAsText())
                val messages = logAppender.events.map { it.formattedMessage }
                assertTrue(
                    messages.any { it.contains(java.util.concurrent.CancellationException::class.java.name) },
                )
            } finally {
                executor.shutdownNow()
            }
        }

    // This test checks isRealCancellationOfTheCall() on its own, with no
    // route and no HTTP call. The earlier name of this test ("a real
    // cancellation of the call coroutine still cancels, and hides
    // nothing") promised a route behaviour, but the test never installs
    // the route (the third security review of this pull request, MINOR
    // 1). The name below states what the test really checks.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `isRealCancellationOfTheCall is true only for a cancelled coroutine`(): Unit = runBlocking {
        val slowStoreStarted = CompletableDeferred<Unit>()
        val sawRealCancellation = CompletableDeferred<Boolean>()
        val respondedInstead = AtomicBoolean(false)

        val job = launch {
            try {
                slowStoreStarted.complete(Unit)
                delay(5000) // A store that waits, cancelled from the outside.
            } catch (cause: CancellationException) {
                sawRealCancellation.complete(isRealCancellationOfTheCall())
                if (isRealCancellationOfTheCall()) {
                    throw cause
                }
                respondedInstead.set(true)
            }
        }

        slowStoreStarted.await()
        job.cancel()
        job.join()

        assertTrue(sawRealCancellation.await(), "Expected isRealCancellationOfTheCall() to see a cancelled coroutine.")
        assertFalse(respondedInstead.get(), "Expected no code path to treat this as a defect of the app.")
        assertTrue(job.isCancelled)
    }

    @Test
    fun `a UserIdResolver that returns an invalid value gives 500 and never 400`() = testApplication {
        val store = InMemoryEventLogStore()
        application {
            routing {
                octometerIngestRoute(store = store, settings = IngestSettings(true)) { "" }
            }
        }

        val response = client.post(DEFAULT_INGEST_PATH) {
            contentType(ContentType.Application.Json)
            setBody(validBody)
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("", response.bodyAsText())
        assertTrue(store.events().isEmpty())
    }

    @Test
    fun `a resolveUserId that throws gives 500 and never 400`() = testApplication {
        val store = InMemoryEventLogStore()
        application {
            routing {
                octometerIngestRoute(store = store, settings = IngestSettings(true)) {
                    throw IllegalArgumentException("the app resolver fails")
                }
            }
        }

        val response = client.post(DEFAULT_INGEST_PATH) {
            contentType(ContentType.Application.Json)
            setBody(validBody)
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertTrue(store.events().isEmpty())
    }

    @Test
    fun `a resolveUserId that throws an IngestException gives 500 and never 400`() = testApplication {
        val store = InMemoryEventLogStore()
        application {
            routing {
                octometerIngestRoute(store = store, settings = IngestSettings(true)) {
                    throw IngestException(IngestException.Reason.INVALID_JSON, "the app resolver fails")
                }
            }
        }

        val response = client.post(DEFAULT_INGEST_PATH) {
            contentType(ContentType.Application.Json)
            setBody(validBody)
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertTrue(store.events().isEmpty())
    }

    @Test
    fun `a dropped anonymous batch still gives 204`() = testApplication {
        val store = InMemoryEventLogStore()
        application {
            routing {
                octometerIngestRoute(store = store, settings = IngestSettings(false)) { null }
            }
        }

        val response = client.post(DEFAULT_INGEST_PATH) {
            contentType(ContentType.Application.Json)
            setBody(validBody)
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertTrue(store.events().isEmpty())
    }

    @Test
    fun `an empty ingestPath throws IllegalArgumentException`() = testApplication {
        val store = InMemoryEventLogStore()
        var threw = false
        application {
            routing {
                try {
                    octometerIngestRoute(store = store, ingestPath = "") { null }
                } catch (expected: IllegalArgumentException) {
                    threw = true
                }
            }
        }
        client.post("/") { }
        assertTrue(threw)
    }

    @Test
    fun `an ingestPath with no leading slash throws IllegalArgumentException`() = testApplication {
        val store = InMemoryEventLogStore()
        var threw = false
        application {
            routing {
                try {
                    octometerIngestRoute(store = store, ingestPath = "api/octometer/v1/clicks") { null }
                } catch (expected: IllegalArgumentException) {
                    threw = true
                }
            }
        }
        client.post("/") { }
        assertTrue(threw)
    }

    private fun exactlySizedBody(totalBytes: Int): String {
        val prefix = """{"sessionId":"0b0e4e0e-6a55-4c1e-9a53-0c1f6f7a2d11","clicks":[{"element":"""" +
            """checkout.save","ageMs":1200,"pad":""""
        val suffix = "\"}]}"
        val padLength = totalBytes - prefix.toByteArray(Charsets.UTF_8).size - suffix.toByteArray(Charsets.UTF_8).size
        assertTrue(padLength >= 0, "The prefix and the suffix alone are already $totalBytes bytes or more.")
        return prefix + "a".repeat(padLength) + suffix
    }

    private fun oversizedJsonBody(): String {
        val oversizedElement = "a".repeat(10 * 1024 * 1024)
        return """{"sessionId":"0b0e4e0e-6a55-4c1e-9a53-0c1f6f7a2d11",""" +
            """"clicks":[{"element":"$oversizedElement","ageMs":1200}]}"""
    }

    /** Captures each log line of one logger, for a design decision D15 test. */
    private class CapturingAppender : AppenderBase<ILoggingEvent>() {
        val events: MutableList<ILoggingEvent> = CopyOnWriteArrayList()

        override fun append(eventObject: ILoggingEvent) {
            events.add(eventObject)
        }
    }
}
