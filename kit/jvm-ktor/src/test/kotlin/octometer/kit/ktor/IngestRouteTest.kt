package octometer.kit.ktor

import ch.qos.logback.classic.Level
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
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.measureTimeMillis
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
import octometer.kit.core.path.PathPatternMatcher
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
    fun `a batch that the store drops above the event cap still gives 204 and an empty body`() = testApplication {
        // Issue #34: the store of design decision D21 drops a batch above
        // OCTOMETER_MAX_EVENTS with no exception; the route then answers
        // 204, the same answer as a stored batch (contract rule C19). This
        // test stands in for that store with a fake that always drops, so
        // this module needs no MongoDB dependency for the check.
        val store = object : EventLogStore {
            override fun append(events: List<IngestEvent>, userId: String?) {
                // The event cap of design decision D21: the store drops the
                // whole batch and writes no event.
            }

            override fun deleteByUserId(userId: String): DeletionResult {
                // Not used by this test.
                return DeletionResult(0, 0, true)
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

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals("", response.bodyAsText(), "The answer must hold no body, so a client learns nothing.")
    }

    // The two tests below prove the route seam of contract rule C42
    // (issue #104): octometerIngestRoute passes settings, and so the
    // path pattern matcher, straight to IngestPipeline.ingest. The Java
    // review of pull request #156 found that no earlier test of this
    // module read a stored path value.

    private val pathBody = """{"sessionId":"0b0e4e0e-6a55-4c1e-9a53-0c1f6f7a2d11",""" +
        """"clicks":[{"element":"checkout.save","ageMs":1200,"path":"/history/42"}]}"""

    @Test
    fun `a route with a path pattern matcher stores the match result`() = testApplication {
        val store = InMemoryEventLogStore()
        val matcher = PathPatternMatcher.of(listOf("/", "/articles", "/articles/*", "/history/:id"))
        application {
            routing {
                octometerIngestRoute(store = store, settings = IngestSettings(true, matcher)) { "user-1" }
            }
        }

        val response = client.post(DEFAULT_INGEST_PATH) {
            contentType(ContentType.Application.Json)
            setBody(pathBody)
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals("/history/:id", store.events()[0].path())
    }

    @Test
    fun `a route with no path pattern list stores no path`() = testApplication {
        val store = InMemoryEventLogStore()
        application {
            routing {
                octometerIngestRoute(store = store, settings = IngestSettings(true)) { "user-1" }
            }
        }

        val response = client.post(DEFAULT_INGEST_PATH) {
            contentType(ContentType.Application.Json)
            setBody(pathBody)
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertNull(store.events()[0].path())
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

    // `IngestRequestProcessorTest` of `kit/jvm-core` now holds the pure
    // order test of a content type other than JSON (415):
    // `textPlainGives415` and `anAbsentContentTypeGives415` (issue #69).

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

    // `IngestRequestProcessorTest` of `kit/jvm-core` now holds the pure
    // order test of an invalid body (400) and of the real body size
    // check at exactly 16384 and 16385 bytes: `anInvalidBodyGives400`,
    // `aBodyOfExactly16384BytesGives204`, and
    // `aBodyOfExactly16385BytesGives400` (issue #69).

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
                // This test never calls deleteByUserId. A call by
                // mistake must fail loudly, not pass with a quiet
                // DeletionResult(0, 0, true).
                throw UnsupportedOperationException()
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
                // This test never calls deleteByUserId. A call by
                // mistake must fail loudly, not pass with a quiet
                // DeletionResult(0, 0, true).
                throw UnsupportedOperationException()
            }
        }
        // A distinct user id for each call (design decision D20, issue #33):
        // 64 calls under one user id would break the 30-request window
        // limit and turn most of them into a fast 429, never a slow store
        // call, which this test does not want to check.
        val userIdCounter = AtomicInteger()
        application {
            routing {
                octometerIngestRoute(store = store, settings = IngestSettings(true)) {
                    "user-${userIdCounter.incrementAndGet()}"
                }
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
                // This test never calls deleteByUserId. A call by
                // mistake must fail loudly, not pass with a quiet
                // DeletionResult(0, 0, true).
                throw UnsupportedOperationException()
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
                // This test never calls deleteByUserId. A call by
                // mistake must fail loudly, not pass with a quiet
                // DeletionResult(0, 0, true).
                throw UnsupportedOperationException()
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
                    // This test never calls deleteByUserId. A call by
                    // mistake must fail loudly, not pass with a quiet
                    // DeletionResult(0, 0, true).
                    throw UnsupportedOperationException()
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
                // This test never calls deleteByUserId. A call by
                // mistake must fail loudly, not pass with a quiet
                // DeletionResult(0, 0, true).
                throw UnsupportedOperationException()
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
                        // This test never calls deleteByUserId. A call
                        // by mistake must fail loudly, not pass with a
                        // quiet DeletionResult(0, 0, true).
                        throw UnsupportedOperationException()
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

    // The tests below check the bot filter and the daily anonymous caps
    // of design decision D43 and issue #117.

    // `IngestRequestProcessorTest` of `kit/jvm-core` now holds the pure
    // order test of the bot filter (204) against a Googlebot value and
    // against a value that only matches with no letter case:
    // `theBotFilterAnswers204AndNeverReachesTheDeclaredSizeCheck` (issue
    // #69; `BotUserAgentFilterTest` already proves the letter-case rule
    // on its own).

    @Test
    fun `a common browser user agent still stores its events`() = testApplication {
        val store = InMemoryEventLogStore()
        application {
            routing {
                octometerIngestRoute(store = store, settings = IngestSettings(true), clock = fixedClock) { null }
            }
        }

        val response = client.post(DEFAULT_INGEST_PATH) {
            contentType(ContentType.Application.Json)
            header(
                HttpHeaders.UserAgent,
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/128.0.0.0 Safari/537.36",
            )
            setBody(validBody)
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals(1, store.events().size)
    }

    // `IngestRequestProcessorTest` of `kit/jvm-core` now holds the pure
    // order test of an absent `User-Agent` header, the DEBUG log line of
    // one robot drop and of a second drop inside one hour, and the
    // 512-character cut of an 8 KB header value:
    // `anAbsentUserAgentHeaderPassesTheBotFilter`,
    // `aRobotDropWritesOneDebugLineWithoutTheHeaderValue`,
    // `aSecondRobotDropInsideOneHourWritesNoLineAndTheNextHourReportsBoth`,
    // `anEightKilobyteUserAgentValueWithABotTokenNearItsStartStillGives204`,
    // and
    // `aBotTokenPastTheFirst512CharactersOfAnEightKilobyteUserAgentValueNeverMatches`
    // (issue #69). The DEBUG line itself now comes from
    // `octometer.kit.core`, not from the application logger of this
    // module, so this class can no longer capture it.

    // `IngestRequestProcessorTest` of `kit/jvm-core` now holds the pure
    // order test of the daily anonymous cap (204) and of the daily cap
    // key sharing the rate limiter's own normalised address:
    // `aBatchAboveTheDailyCapGives204AndTheStoreHoldsNoNewEvent`,
    // `aSignedInUserNeverPaysTheDailyAnonymousCap`, and the client
    // address tests of that class (issue #69).

    @Test
    fun `a marker user agent and a marker address stay out of each captured log line`() = testApplication {
        val store = InMemoryEventLogStore()
        val markerUserAgent = "SENTINEL-bot-marker-user-agent-host-octo-shard-00"
        val markerAddress = "203.0.113.77"
        application {
            routing {
                octometerIngestRoute(
                    store = store,
                    settings = IngestSettings(true),
                    clock = fixedClock,
                    clientIpHeaderName = "X-Client-Ip",
                ) { null }
            }
        }

        val response = client.post(DEFAULT_INGEST_PATH) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.UserAgent, markerUserAgent)
            header("X-Client-Ip", markerAddress)
            setBody(validBody)
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertTrue(store.events().isEmpty())
        val messages = logAppender.events.map { it.formattedMessage }
        assertTrue(messages.isNotEmpty(), "Expected the bot drop to write at least one log line.")
        assertTrue(messages.none { it.contains(markerUserAgent) })
        assertTrue(messages.none { it.contains(markerAddress) })
    }

    // The tests below check the ingest rate limit of design decision D20
    // and issue #33. `octometerIngestRoute` builds one `IngestRateLimiter`
    // by default when a test installs the route, so each test below gets
    // its own limiter and its own window.

    @Test
    fun `request 31 of one user id in 60 seconds gives 429, and a request after the window passes`() =
        testApplication {
            val store = InMemoryEventLogStore()
            val clock = MutableClock(fixedClock.instant())
            application {
                routing {
                    octometerIngestRoute(store = store, settings = IngestSettings(true), clock = clock) { "user-1" }
                }
            }

            repeat(30) { requestIndex ->
                val response = client.post(DEFAULT_INGEST_PATH) {
                    contentType(ContentType.Application.Json)
                    setBody(validBody)
                }
                assertEquals(HttpStatusCode.NoContent, response.status, "Request ${requestIndex + 1} of 30 must pass.")
            }

            val limitedResponse = client.post(DEFAULT_INGEST_PATH) {
                contentType(ContentType.Application.Json)
                setBody(validBody)
            }
            assertEquals(HttpStatusCode.TooManyRequests, limitedResponse.status)
            assertEquals("", limitedResponse.bodyAsText())
            assertEquals(30, store.events().size)

            clock.advance(Duration.ofSeconds(60))

            val afterWindowResponse = client.post(DEFAULT_INGEST_PATH) {
                contentType(ContentType.Application.Json)
                setBody(validBody)
            }
            assertEquals(HttpStatusCode.NoContent, afterWindowResponse.status)
        }

    @Test
    fun `without a user id, and with anonymous clicks off, request 121 of one client address gives 429`() =
        testApplication {
            // With anonymous clicks off, design decision D43 never runs
            // (issue #116, BLOCKER 1 of the review of pull request
            // #197). The client-address limit of design decision D20
            // stays the only per-minute limit of this request.
            val store = InMemoryEventLogStore()
            application {
                routing {
                    octometerIngestRoute(store = store, settings = IngestSettings(false), clock = fixedClock) { null }
                }
            }

            repeat(120) { requestIndex ->
                val response = client.post(DEFAULT_INGEST_PATH) {
                    contentType(ContentType.Application.Json)
                    setBody(validBody)
                }
                assertEquals(HttpStatusCode.NoContent, response.status, "Request ${requestIndex + 1} of 120 must pass.")
            }

            val limitedResponse = client.post(DEFAULT_INGEST_PATH) {
                contentType(ContentType.Application.Json)
                setBody(validBody)
            }
            assertEquals(HttpStatusCode.TooManyRequests, limitedResponse.status)
            // Anonymous clicks are off, so the route stores no event for
            // any of the 120 passed requests (design decision D19).
            assertEquals(0, store.events().size)
        }

    @Test
    fun `with anonymous clicks on, request 301 of one client address gives 429, and request 300 passes`() =
        testApplication {
            // Design decision D43 replaces the 120-request limit of D20
            // for a request with no user id, when the app records an
            // anonymous click (BLOCKER 1 of the review of pull request
            // #197). This test uses the default minute limiter, built
            // from the default per-minute limits of `IngestSettings`
            // (300 requests each 60 seconds), and no user id, so the
            // request counter of D43 is the only per-minute limit here.
            val store = InMemoryEventLogStore()
            application {
                routing {
                    octometerIngestRoute(store = store, settings = IngestSettings(true), clock = fixedClock) { null }
                }
            }

            repeat(300) { requestIndex ->
                val response = client.post(DEFAULT_INGEST_PATH) {
                    contentType(ContentType.Application.Json)
                    setBody(validBody)
                }
                assertEquals(HttpStatusCode.NoContent, response.status, "Request ${requestIndex + 1} of 300 must pass.")
            }

            val limitedResponse = client.post(DEFAULT_INGEST_PATH) {
                contentType(ContentType.Application.Json)
                setBody(validBody)
            }
            assertEquals(HttpStatusCode.TooManyRequests, limitedResponse.status)
            // The 300 earlier, allowed requests already stored one event
            // each; the limited 301st request must add no new one.
            assertEquals(300, store.events().size)
        }

    @Test
    fun `a 429 answer arrives even for a body that is not valid JSON`() = testApplication {
        val store = InMemoryEventLogStore()
        application {
            routing {
                octometerIngestRoute(store = store, settings = IngestSettings(true), clock = fixedClock) { "user-1" }
            }
        }

        repeat(30) {
            client.post(DEFAULT_INGEST_PATH) {
                contentType(ContentType.Application.Json)
                setBody(validBody)
            }
        }

        // A body that IngestPipeline.process would reject with 400. The
        // route must never reach that parse step once the limiter
        // rejects the request (issue #33, step 4): the answer is 429,
        // not 400.
        val response = client.post(DEFAULT_INGEST_PATH) {
            contentType(ContentType.Application.Json)
            setBody("this is not valid JSON")
        }
        assertEquals(HttpStatusCode.TooManyRequests, response.status)
    }

    // `IngestRequestProcessorTest` of `kit/jvm-core` now holds the pure
    // order test of the client address (design decision D20, issue #33;
    // design decision D43, issue #116): two different header values, a
    // spoofed header with no configured header name, a request with no
    // such header, a forged left element, a value above 64 characters, a
    // value with no IP address form, and each `trustedProxyCount` case
    // (issue #69). Each test of that class uses a 1-request minute limit
    // instead of a 120-request loop, to prove the very same resolved key
    // with no need for a slow, repeated loop.

    @Test
    fun `a 429 answer writes no line of its own to the application log of the route`() = testApplication {
        // IngestRateLimiter writes its own warning through java.base
        // System.Logger (see IngestRateLimiterTest of kit/jvm-core, which
        // checks that line holds no key). This test checks a different
        // rule: the route itself, like its 400 and 415 answers, calls
        // respondWithDefect for no 429 answer, so it writes no defect
        // line of its own (design decision D15), and that line never
        // holds the client address or the user id of the request.
        val store = InMemoryEventLogStore()
        val knownClientAddress = "203.0.113.55"
        application {
            routing {
                octometerIngestRoute(
                    store = store,
                    settings = IngestSettings(false),
                    clock = fixedClock,
                    clientIpHeaderName = "X-Client-Ip",
                ) { null }
            }
        }

        repeat(121) {
            client.post(DEFAULT_INGEST_PATH) {
                contentType(ContentType.Application.Json)
                header("X-Client-Ip", knownClientAddress)
                setBody(validBody)
            }
        }

        val messages = logAppender.events.map { it.formattedMessage }
        assertTrue(messages.none { it.contains("The Octometer ingest route failed") })
        assertTrue(
            messages.none { it.contains(knownClientAddress) },
            "No log line of the route must hold the client address.",
        )
    }

    @Test
    fun `a client already limited gets 429 for a body above the maximum size, never 400`() = testApplication {
        // Security review MINOR 2 and concurrency review MINOR 7 of pull
        // request #158: no test held the order of the rate limit check
        // and the body read. An oversized body gives 400 only when the
        // route reads the body. It must never reach that step once a
        // client is already limited (issue #33, step 4).
        //
        // The maintainer named a second correction on 2026-09-22 (Java
        // review MAJOR 3). The rate limiter must also run before the
        // declared-length check. A limited client with a declared
        // oversized Content-Length must still get 429, not 400. This
        // test sends a plain oversized body with its normal declared
        // Content-Length, the original form of this test.
        val store = InMemoryEventLogStore()
        application {
            routing {
                octometerIngestRoute(store = store, settings = IngestSettings(false), clock = fixedClock) { null }
            }
        }

        repeat(120) { requestIndex ->
            val response = client.post(DEFAULT_INGEST_PATH) {
                contentType(ContentType.Application.Json)
                setBody(validBody)
            }
            assertEquals(HttpStatusCode.NoContent, response.status, "Request ${requestIndex + 1} of 120 must pass.")
        }

        val oversizedBody = """{"sessionId":"${"a".repeat(20_000)}"}"""
        val response = client.post(DEFAULT_INGEST_PATH) {
            contentType(ContentType.Application.Json)
            setBody(oversizedBody)
        }
        assertEquals(HttpStatusCode.TooManyRequests, response.status)
    }

    @Test
    fun `positiveWholeNumberFromEnvironmentValue gives the default for a null value`() {
        assertEquals(1, positiveWholeNumberFromEnvironmentValue(null, "OCTOMETER_TRUSTED_PROXY_COUNT", 1))
    }

    @Test
    fun `positiveWholeNumberFromEnvironmentValue stops the app start for a zero value with no repeat of the value`() {
        val exception = assertFailsWith<IllegalStateException> {
            positiveWholeNumberFromEnvironmentValue("0", "OCTOMETER_TRUSTED_PROXY_COUNT", 1)
        }
        assertTrue(exception.message!!.contains("OCTOMETER_TRUSTED_PROXY_COUNT"))
        assertFalse(exception.message!!.contains("=0"))
    }

    @Test
    fun `positiveWholeNumberFromEnvironmentValue stops the app start for a negative value`() {
        assertFailsWith<IllegalStateException> {
            positiveWholeNumberFromEnvironmentValue("-1", "OCTOMETER_TRUSTED_PROXY_COUNT", 1)
        }
    }

    @Test
    fun `positiveWholeNumberFromEnvironmentValue stops the app start for a text value with no repeat of the value`() {
        val exception = assertFailsWith<IllegalStateException> {
            positiveWholeNumberFromEnvironmentValue("many", "OCTOMETER_TRUSTED_PROXY_COUNT", 1)
        }
        assertFalse(exception.message!!.contains("many"))
    }

    // `IngestRequestProcessorTest` of `kit/jvm-core` now holds the pure
    // order test of the anonymous per-minute limiter of design decision
    // D43 and issue #116: the request counter before the body read, the
    // click entry counter and the session-start counter after the parse,
    // a signed-in user paying neither counter, the limiter staying off
    // with no anonymous click, and its log line holding no address
    // (issue #69). `AnonymousMinuteLimiterTest` of `kit/jvm-core` already
    // proves the exact acceptance numbers 300, 900, and 120.

    /** A [Clock] that a test can move forward, for a rate-limit window test. */
    private class MutableClock(startInstant: Instant) : Clock() {
        @Volatile
        private var current: Instant = startInstant

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock {
            throw UnsupportedOperationException("This test clock always uses UTC.")
        }

        override fun instant(): Instant = current
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
