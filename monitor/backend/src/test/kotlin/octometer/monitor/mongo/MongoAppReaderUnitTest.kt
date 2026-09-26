package octometer.monitor.mongo

import ch.qos.logback.classic.Level
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.reactivestreams.client.MongoClient as ReactiveMongoClient
import com.mongodb.reactivestreams.client.MongoClients as ReactiveMongoClients
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.util.Date
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import octometer.monitor.captureLogEvents
import octometer.monitor.registerTempRoot
import octometer.monitor.store.EventStore
import octometer.monitor.store.SqliteDatabase
import org.bson.Document
import org.bson.types.ObjectId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * This class holds the tests of issue #16 that need no Docker. They
 * cover the parse of one event document (contract rules C1 to C6, C9),
 * the filter of section 4.3, and the bound of section 4.3. They also
 * cover the page loop of [MongoAppReader.runCycle] with a fake page
 * source. `MongoAppReaderContainerTest` covers the real MongoDB read
 * and the client reuse of design decision D10.
 *
 * This class also holds the tests of correction round 1 of pull
 * request #160: BLOCKER 1 and MAJOR 2 of the security review. It also
 * holds BLOCKER 1, BLOCKER 2, and MAJOR 1 of the Kotlin review.
 *
 * Issue #27 adds the tests of [invalidReason], the `skipped_event` row,
 * and the status of a poll cycle (design decisions D5, D8).
 */
class MongoAppReaderUnitTest {

    private val root = Files.createTempDirectory("octometer-mongo-reader-test-").toFile().also { registerTempRoot(it) }
    private val dataDir = File(root, "data")
    private lateinit var database: SqliteDatabase
    private lateinit var eventStore: EventStore
    private lateinit var reader: MongoAppReader
    private var appId: Long = 0

    @BeforeTest
    fun setUp() = runBlocking {
        database = SqliteDatabase.open(dataDir.absolutePath)
        eventStore = EventStore(database)
        reader = MongoAppReader(eventStore, settleLagSeconds = 2)
        appId = insertApp(database, "demo")
    }

    @AfterTest
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    // --- parseEvent (contract rules C1 to C6, C9) ---

    @Test
    fun `parseEvent reads the five named fields`() {
        val id = ObjectId()
        val ts = Date(1_700_000_000_000L)
        val document = Document("_id", id)
            .append("ts", ts)
            .append("element", "checkout.save")
            .append("sessionId", "session-1")
            .append("userId", "user-1")

        val event = parseEvent(document)

        assertEquals(id.toHexString(), event.eventId)
        assertEquals(1_700_000_000_000L, event.ts)
        assertEquals("checkout.save", event.element)
        assertEquals("session-1", event.sessionId)
        assertEquals("user-1", event.userId)
    }

    // --- path, referrerHost, and kind (issue #110, design decision D5) ---

    @Test
    fun `parseEvent copies path as it is, for a click and for a session start`() {
        val click = goodDocument("checkout.save").append("path", "/checkout")
        val sessionStart = goodDocument(SESSION_START_ELEMENT).append("path", "/")

        assertEquals("/checkout", parseEvent(click).path)
        assertEquals("/", parseEvent(sessionStart).path)
    }

    @Test
    fun `parseEvent copies referrerHost as it is, for a session start`() {
        val document = goodDocument(SESSION_START_ELEMENT).append("referrerHost", "google.com")

        val event = parseEvent(document)

        assertEquals("google.com", event.referrerHost)
    }

    // BLOCKER 1 of the security review of pull request #193: section 6
    // states "it copies referrer_host from a session start". Contract
    // rule C40 drops the field on each other element.
    @Test
    fun `parseEvent gives a null referrerHost for a click, even when the field is present`() {
        val document = goodDocument("checkout.save").append("referrerHost", "google.com")

        val event = parseEvent(document)

        assertNull(event.referrerHost)
    }

    @Test
    fun `parseEvent gives a null path and a null referrerHost when the fields are absent`() {
        val event = parseEvent(goodDocument("checkout.save"))

        assertNull(event.path)
        assertNull(event.referrerHost)
    }

    @Test
    fun `parseEvent sets kind 1 for the element octo-session-start`() {
        val event = parseEvent(goodDocument(SESSION_START_ELEMENT))

        assertEquals(KIND_SESSION_START, event.kind)
    }

    @Test
    fun `parseEvent sets kind 0 for each other element`() {
        val event = parseEvent(goodDocument("checkout.save"))

        assertEquals(KIND_CLICK, event.kind)
    }

    @Test
    fun `invalidReason accepts a document with no path and no referrerHost`() {
        assertNull(invalidReason(goodDocument("checkout.save")))
    }

    @Test
    fun `invalidReason names a wrong BSON type of path and of referrerHost`() {
        val wrongPath = goodDocument("checkout.save").append("path", 42)
        val wrongReferrerHost = goodDocument(SESSION_START_ELEMENT).append("referrerHost", 42)

        assertEquals("path wrong type", invalidReason(wrongPath))
        assertEquals("referrerHost wrong type", invalidReason(wrongReferrerHost))
    }

    @Test
    fun `invalidReason names a path above the contract limit of 150 bytes`() {
        val tooLong = goodDocument("checkout.save").append("path", "/" + "a".repeat(PATH_MAX_BYTES))

        assertEquals("path too long", invalidReason(tooLong))
    }

    @Test
    fun `invalidReason names a referrerHost above the contract limit of 253 bytes`() {
        val tooLong = goodDocument(SESSION_START_ELEMENT).append("referrerHost", "a".repeat(REFERRER_HOST_MAX_BYTES + 1))

        assertEquals("referrerHost too long", invalidReason(tooLong))
    }

    @Test
    fun `runCycle stores the path, the referrerHost, and the kind of a session start`() = runBlocking {
        val sessionStart = goodDocument(SESSION_START_ELEMENT)
            .append("path", "/")
            .append("referrerHost", "google.com")

        reader.runCycle(appId, null, MAX_PAGES_PER_CYCLE, PAGE_LIMIT) { _ -> listOf(sessionStart) }

        val row = readEventColumns(database, appId, sessionStart.getObjectId("_id").toHexString())
        assertEquals("/", row.path)
        assertEquals("google.com", row.referrerHost)
        assertEquals(KIND_SESSION_START, row.kind)
    }

    @Test
    fun `runCycle stores NULL for path and referrerHost when the document has neither field`() = runBlocking {
        val click = goodDocument("checkout.save")

        reader.runCycle(appId, null, MAX_PAGES_PER_CYCLE, PAGE_LIMIT) { _ -> listOf(click) }

        val row = readEventColumns(database, appId, click.getObjectId("_id").toHexString())
        assertNull(row.path)
        assertNull(row.referrerHost)
        assertEquals(KIND_CLICK, row.kind)
    }

    // BLOCKER 1 of the security review of pull request #193.
    @Test
    fun `runCycle stores NULL for referrerHost when a click document holds the field`() = runBlocking {
        val click = goodDocument("checkout.save").append("referrerHost", "google.com")

        reader.runCycle(appId, null, MAX_PAGES_PER_CYCLE, PAGE_LIMIT) { _ -> listOf(click) }

        val row = readEventColumns(database, appId, click.getObjectId("_id").toHexString())
        assertNull(row.referrerHost)
        assertEquals(KIND_CLICK, row.kind)
    }

    @Test
    fun `runCycle skips a document with a wrong BSON type in path, and moves the cursor`() = runBlocking {
        val bad = goodDocument("checkout.save").append("path", 42)
        val good = goodDocument("good.click")
        val page = listOf(bad, good)

        val outcome = reader.runCycle(appId, null, MAX_PAGES_PER_CYCLE, PAGE_LIMIT) { _ -> page }

        assertEquals(1, outcome.eventsStored)
        assertEquals(1, outcome.eventsSkipped)
        assertEquals(page.last().getObjectId("_id").toHexString(), outcome.cursor, "The cursor must move past the skip.")
        assertEquals("path wrong type", readSkippedReason(database, appId, bad.getObjectId("_id").toHexString()))
    }

    @Test
    fun `parseEvent accepts a document with an unknown field`() {
        val document = Document("_id", ObjectId())
            .append("ts", Date(1_700_000_000_000L))
            .append("element", "checkout.save")
            .append("sessionId", "session-1")
            .append("userId", null)
            .append("aNewFieldTheReaderDoesNotKnow", "some value")

        val event = parseEvent(document)

        assertEquals("checkout.save", event.element)
        assertNull(event.userId)
    }

    // --- idFilter (section 4.3, note of the review of pull request #83) ---

    @Test
    fun `idFilter without a cursor holds no gt term`() {
        val bound = ObjectId.getSmallestWithDate(Date(1_700_000_000_000L))

        val filter = idFilter(cursor = null, bound = bound).toBsonDocument()

        assertFalse(filter.getDocument("_id").containsKey("\$gt"), "A first cycle must not filter with \$gt: null.")
        assertEquals(bound, filter.getDocument("_id").getObjectId("\$lt").value)
    }

    @Test
    fun `idFilter with a cursor holds both terms`() {
        val cursor = ObjectId()
        val bound = ObjectId.getSmallestWithDate(Date(1_700_000_000_000L))

        val filter = idFilter(cursor = cursor.toHexString(), bound = bound).toBsonDocument()

        assertEquals(cursor, filter.getDocument("_id").getObjectId("\$gt").value)
        assertEquals(bound, filter.getDocument("_id").getObjectId("\$lt").value)
    }

    // --- boundObjectId (section 4.3: the server time of the hello command, never a local clock) ---

    @Test
    fun `boundObjectId subtracts the lag from the server time, not from a local clock`() {
        val serverTime = Date(1_700_000_060_000L)

        val bound = boundObjectId(serverTime, settleLagSeconds = 60)

        assertEquals(ObjectId.getSmallestWithDate(Date(1_700_000_000_000L)), bound)
    }

    // --- runCycle (step 5, D4) with a fake page source, no Docker ---

    @Test
    fun `runCycle commits each page and stops at the page cap`() = runBlocking {
        val pages = (1..12).map { pageIndex -> fakePage(pageIndex, PAGE_LIMIT) }
        var callCount = 0

        val outcome = reader.runCycle(appId, null, MAX_PAGES_PER_CYCLE, PAGE_LIMIT) { _ ->
            pages[callCount].also { callCount += 1 }
        }

        assertEquals(MAX_PAGES_PER_CYCLE, outcome.pagesRead)
        assertEquals(MAX_PAGES_PER_CYCLE * PAGE_LIMIT, outcome.eventsStored)
        assertEquals(MAX_PAGES_PER_CYCLE, callCount, "The cap must stop the loop; page 11 must stay unread.")
        assertEquals(countEvents(database, appId), outcome.eventsStored)
    }

    @Test
    fun `runCycle stops at the first page smaller than the page limit`() = runBlocking {
        val pages = listOf(fakePage(1, PAGE_LIMIT), fakePage(2, 137), fakePage(3, PAGE_LIMIT))
        var callCount = 0

        val outcome = reader.runCycle(appId, null, MAX_PAGES_PER_CYCLE, PAGE_LIMIT) { _ ->
            pages[callCount].also { callCount += 1 }
        }

        assertEquals(2, outcome.pagesRead)
        assertEquals(PAGE_LIMIT + 137, outcome.eventsStored)
        assertEquals(2, callCount, "The third, unread page must stay out of the count.")
    }

    @Test
    fun `runCycle passes the cursor of the previous page to the next fetch`() = runBlocking {
        // A page limit of 2 matches the size of page1, so the loop treats
        // it as a full page and asks for one page more.
        val page1 = fakePage(1, 2)
        val page2 = fakePage(2, 1)
        val seenCursors = mutableListOf<String?>()

        reader.runCycle(appId, null, MAX_PAGES_PER_CYCLE, pageLimit = 2) { cursor ->
            seenCursors += cursor
            if (cursor == null) page1 else page2
        }

        assertEquals(listOf(null, page1.last().getObjectId("_id").toHexString()), seenCursors)
    }

    @Test
    fun `runCycle stores 0 events when the first page is empty`() = runBlocking {
        val outcome = reader.runCycle(appId, "cursor-1", MAX_PAGES_PER_CYCLE, PAGE_LIMIT) { emptyList() }

        assertEquals(0, outcome.eventsStored)
        assertEquals(0, outcome.pagesRead)
        assertEquals("cursor-1", outcome.cursor, "An empty cycle must not lose the cursor of the app row.")
    }

    // --- The cursor guard of the correction round (issue #187, MAJOR 1 of pull request #208) ---

    // The maintainer's decision: a PATCH of the connection string can
    // reset the cursor between the read of a page and its commit. The
    // fetchPage hook below mimics that reset, right before runCycle
    // reaches commitPage for the one page it read.
    @Test
    fun `runCycle leaves the cursor at NULL when a reset runs between the page read and its commit`() = runBlocking {
        setCursor(database, appId, "old-cursor")
        val page = fakePage(1, PAGE_LIMIT)

        reader.runCycle(appId, "old-cursor", MAX_PAGES_PER_CYCLE, PAGE_LIMIT) { _ ->
            resetCursor(database, appId)
            page
        }

        assertNull(readCursor(database, appId), "the reset must stay; a stale commit must not restore the old cursor")
        assertEquals(0, countEvents(database, appId), "the rejected page must store no event")
    }

    // --- The invalid-document skip (Kotlin review, MAJOR 2 of pull request #160) ---

    @Test
    fun `runCycle skips an invalid document, moves the cursor, and stores the rest`() = runBlocking {
        val good1 = goodDocument("good.first")
        val bad = Document("_id", ObjectId()).append("element", 42)
        val good2 = goodDocument("good.second")
        val page = listOf(good1, bad, good2)

        val (outcome, events) = captureLogEvents {
            reader.runCycle(appId, null, MAX_PAGES_PER_CYCLE, PAGE_LIMIT) { _ -> page }
        }

        assertEquals(2, outcome.eventsStored, "The reader must skip the one invalid document.")
        assertEquals(1, outcome.eventsSkipped, "The outcome must count the one skipped document.")
        assertEquals(page.last().getObjectId("_id").toHexString(), outcome.cursor, "The cursor must move past the invalid document.")
        assertEquals(2, countEvents(database, appId))
        val warnLines = events.filter { it.level == Level.WARN && it.loggerName.contains("MongoAppReader") }
        assertEquals(1, warnLines.size, "One WARN line must name the skipped count.")
        assertTrue(warnLines.single().formattedMessage.contains("1"), "The line must hold the skipped count.")
        assertFalse(warnLines.single().formattedMessage.contains("42"), "The line must hold no document content.")
    }

    @Test
    fun `an invalid document does not block the events after it, and the next cycle continues`() = runBlocking {
        val good1 = goodDocument("good.first")
        val bad = Document("_id", ObjectId()).append("element", 42)
        val good2 = goodDocument("good.second")
        val good3 = goodDocument("good.third")
        val firstPage = listOf(good1, bad, good2)
        val secondPage = listOf(good3)

        val firstOutcome = reader.runCycle(appId, null, MAX_PAGES_PER_CYCLE, PAGE_LIMIT) { cursor ->
            assertEquals(null, cursor)
            firstPage
        }
        assertEquals(2, firstOutcome.eventsStored)
        assertEquals(firstPage.last().getObjectId("_id").toHexString(), firstOutcome.cursor)

        val secondOutcome = reader.runCycle(appId, firstOutcome.cursor, MAX_PAGES_PER_CYCLE, PAGE_LIMIT) { cursor ->
            assertEquals(firstOutcome.cursor, cursor)
            secondPage
        }

        assertEquals(1, secondOutcome.eventsStored, "The cycle after the skip must still read the new event.")
        assertEquals(3, countEvents(database, appId))
    }

    // --- invalidReason (design decision D5, decision 1 of issue #27) ---

    @Test
    fun `invalidReason accepts a valid document`() {
        assertNull(invalidReason(goodDocument("checkout.save")))
    }

    @Test
    fun `invalidReason accepts an absent userId field as an anonymous event`() {
        val document = Document("_id", ObjectId())
            .append("ts", Date(1_700_000_000_000L))
            .append("element", "checkout.save")
            .append("sessionId", "session-1")

        assertNull(invalidReason(document), "An absent userId must be a valid anonymous event (contract rule C6).")
    }

    @Test
    fun `invalidReason accepts an explicit null userId as an anonymous event`() {
        val document = goodDocument("checkout.save").append("userId", null)

        assertNull(invalidReason(document))
    }

    @Test
    fun `invalidReason names each missing field`() {
        val noElement = Document("_id", ObjectId()).append("ts", Date(1_700_000_000_000L)).append("sessionId", "session-1")
        val noTs = Document("_id", ObjectId()).append("element", "checkout.save").append("sessionId", "session-1")
        val noSessionId = Document("_id", ObjectId()).append("ts", Date(1_700_000_000_000L)).append("element", "checkout.save")

        assertEquals("element missing", invalidReason(noElement))
        assertEquals("ts missing", invalidReason(noTs))
        assertEquals("sessionId missing", invalidReason(noSessionId))
    }

    @Test
    fun `invalidReason names a wrong BSON type`() {
        val wrongTs = goodDocument("checkout.save").append("ts", "not-a-date")
        val wrongElement = Document("_id", ObjectId())
            .append("ts", Date(1_700_000_000_000L))
            .append("element", 42)
            .append("sessionId", "session-1")
        val wrongSessionId = Document("_id", ObjectId())
            .append("ts", Date(1_700_000_000_000L))
            .append("element", "checkout.save")
            .append("sessionId", 42)
        val wrongUserId = goodDocument("checkout.save").append("userId", 42)

        assertEquals("ts wrong type", invalidReason(wrongTs))
        assertEquals("element wrong type", invalidReason(wrongElement))
        assertEquals("sessionId wrong type", invalidReason(wrongSessionId))
        assertEquals("userId wrong type", invalidReason(wrongUserId))
    }

    @Test
    fun `invalidReason names an empty userId`() {
        val emptyUserId = goodDocument("checkout.save").append("userId", "")

        assertEquals("userId empty", invalidReason(emptyUserId))
    }

    // --- The skipped_event row of runCycle (design decision D5, issue #27) ---

    @Test
    fun `runCycle writes a skipped_event row with the fixed reason`() = runBlocking {
        val bad = Document("_id", ObjectId())
            .append("element", "checkout.save")
            .append("sessionId", "session-1")
            .append("userId", "user-1")
        val good = goodDocument("good.click")
        val page = listOf(bad, good)

        val outcome = reader.runCycle(appId, null, MAX_PAGES_PER_CYCLE, PAGE_LIMIT) { _ -> page }

        assertEquals(1, outcome.eventsStored)
        assertEquals(1, outcome.eventsSkipped)
        assertEquals("ts missing", readSkippedReason(database, appId, bad.getObjectId("_id").toHexString()))
    }

    @Test
    fun `the skipped_event reason holds no field value of the document`() = runBlocking {
        val marker = "octomarkerfieldc9a1"
        val bad = Document("_id", ObjectId())
            .append("ts", marker)
            .append("element", marker)
            .append("sessionId", marker)
            .append("userId", marker)

        reader.runCycle(appId, null, MAX_PAGES_PER_CYCLE, PAGE_LIMIT) { _ -> listOf(bad) }

        val reason = readSkippedReason(database, appId, bad.getObjectId("_id").toHexString())
        assertEquals("ts wrong type", reason)
        assertFalse(reason!!.contains(marker), "The reason must hold no field value of the document: $reason")
    }

    // Acceptance criterion 6 of issue #110: no log line and no
    // skipped_event reason holds a raw path or a raw host.
    @Test
    fun `the skipped_event reason for a bad path or a bad referrerHost holds no field value of the document`() = runBlocking {
        val pathMarker = "/octomarkerpath7c2e"
        val hostMarker = "octomarkerhost7c2e.invalid"
        val badPath = goodDocument("checkout.save").append("path", pathMarker.repeat(20))
        val badReferrerHost = goodDocument(SESSION_START_ELEMENT).append("referrerHost", hostMarker.repeat(20))

        val (_, logEvents) = captureLogEvents {
            reader.runCycle(appId, null, MAX_PAGES_PER_CYCLE, PAGE_LIMIT) { _ -> listOf(badPath, badReferrerHost) }
        }

        val pathReason = readSkippedReason(database, appId, badPath.getObjectId("_id").toHexString())
        val hostReason = readSkippedReason(database, appId, badReferrerHost.getObjectId("_id").toHexString())
        assertEquals("path too long", pathReason)
        assertEquals("referrerHost too long", hostReason)
        assertFalse(pathReason!!.contains(pathMarker), "The reason must hold no raw path: $pathReason")
        assertFalse(hostReason!!.contains(hostMarker), "The reason must hold no raw host: $hostReason")
        logEvents.forEach { event ->
            assertFalse(event.formattedMessage.contains(pathMarker), "A log line must hold no raw path.")
            assertFalse(event.formattedMessage.contains(hostMarker), "A log line must hold no raw host.")
        }
    }

    // --- The cancellation guard of lesson 2 (MAJOR 2 of the security review, MAJOR 1 of the Kotlin review) ---

    @Test
    fun `a foreign CancellationException from inside the driver becomes a MongoReadFailedException`() = runBlocking {
        val marker = "octomarkercancel8a1f"
        val throwingReader = MongoAppReader(
            eventStore,
            settleLagSeconds = 2,
            serverTimeSource = { throw CancellationException(marker) },
        )
        val target = PollTarget(appId, "db", "octometer_events", cursor = null)

        val failure = kotlin.runCatching {
            throwingReader.pollOnce(target, "mongodb://127.0.0.1:1/exampledb")
        }.exceptionOrNull()

        assertTrue(failure is MongoReadFailedException, "Expected a MongoReadFailedException, got $failure.")
        assertFalse(failure.message!!.contains(marker), "The message must hold no text of the foreign cancellation.")
        throwingReader.close()
    }

    // --- The exception map of design decision D8 (issue #28, the maintainer's decision 1) ---

    // The acceptance criterion of issue #28: an unknown exception gives
    // ERROR. mongoFailureStatus of MongoFailureMappingTest already
    // proves this at the pure-function level, with no MongoAppReader.
    // This test proves the wrap site of withMongoFailure applies that
    // same map, through the one path that a real poll cycle can reach.
    @Test
    fun `an unknown exception maps to the status ERROR, with no code`() = runBlocking {
        val unknownReader = MongoAppReader(
            eventStore,
            settleLagSeconds = 2,
            serverTimeSource = { throw java.util.NoSuchElementException("a probe failure of an unknown type") },
        )
        val target = PollTarget(appId, "db", "octometer_events", cursor = null)

        val failure = kotlin.runCatching {
            unknownReader.pollOnce(target, "mongodb://127.0.0.1:1/exampledb")
        }.exceptionOrNull()

        assertTrue(failure is MongoReadFailedException, "Expected a MongoReadFailedException, got $failure.")
        assertEquals(STATUS_ERROR, failure.status)
        assertEquals(null, failure.code)
        unknownReader.close()
    }

    // The acceptance criterion of issue #28: an exception message with
    // a URI gives a last_error without the URI. MongoReadFailedException
    // keeps no message text of the real cause (the maintainer's decision
    // 1), so last_error, built from its status and its code only, can
    // never hold the URI either. markerUri is the one allow-listed fake
    // credential of .gitleaks.toml (octometer.monitor.registry.allowlistedSrvUri).
    @Test
    fun `an exception message with a URI gives a last_error without the URI`() = runBlocking {
        val markerUri = octometer.monitor.registry.allowlistedSrvUri()
        val markedReader = MongoAppReader(
            eventStore,
            settleLagSeconds = 2,
            serverTimeSource = { throw RuntimeException("connect failed for $markerUri") },
        )
        val target = PollTarget(appId, "db", "octometer_events", cursor = null)

        val failure = kotlin.runCatching {
            markedReader.pollOnce(target, "mongodb://127.0.0.1:1/exampledb")
        }.exceptionOrNull()

        assertTrue(failure is MongoReadFailedException, "Expected a MongoReadFailedException, got $failure.")
        val lastError = failure.code?.toString() ?: failure.javaClass.simpleName
        assertFalse(lastError.contains(markerUri), "last_error must hold no URI")
        assertFalse(failure.message!!.contains(markerUri), "the exception message must hold no URI")
        markedReader.close()
    }

    // --- MongoReadFailedException (the security note of the maintainer, BLOCKER 1 of the security review) ---

    @Test
    fun `a failed connect gives one fixed sentence and the exception class, with no host in the message`() = runBlocking {
        val shortTimeoutReader = MongoAppReader(eventStore, settleLagSeconds = 2, clientFactory = ::shortTimeoutClient)
        val target = PollTarget(appId, "db", "octometer_events", cursor = null)

        val failure = kotlin.runCatching {
            shortTimeoutReader.pollOnce(target, "mongodb://127.0.0.1:1/exampledb")
        }.exceptionOrNull()

        assertTrue(failure is MongoReadFailedException, "Expected a MongoReadFailedException, got $failure.")
        assertTrue(failure.message!!.startsWith("The reader could not read MongoDB."))
        assertFalse(failure.message!!.contains("127.0.0.1"), "The message must hold no host.")
        shortTimeoutReader.close()
    }

    @Test
    fun `a failed connect through the default client path keeps the user name, the password, and the port out of the message`() = runBlocking {
        val markerUser = "octomarkeruser7f3a"
        val markerSecondValue = "octomarkerpass9d2e"
        val target = PollTarget(appId, "db", "octometer_events", cursor = null)

        val failure = kotlin.runCatching {
            reader.pollOnce(target, "mongodb://$markerUser:$markerSecondValue@127.0.0.1:1/exampledb")
        }.exceptionOrNull()

        assertTrue(failure is MongoReadFailedException, "Expected a MongoReadFailedException, got $failure.")
        assertTrue(failure.message!!.startsWith("The reader could not read MongoDB."))
        assertFalse(failure.message!!.contains(markerUser), "The message must hold no user name.")
        assertFalse(failure.message!!.contains(markerSecondValue), "The message must hold no password.")
        assertFalse(failure.message!!.contains("127.0.0.1"), "The message must hold no host.")
        assertFalse(failure.message!!.contains(":1/"), "The message must hold no port.")
    }

    @Test
    fun `the connection string check of the registry runs again before the driver, for a value edited by hand`() = runBlocking {
        val target = PollTarget(appId, "db", "octometer_events", cursor = null)
        // "readPreference" is a real driver option that the driver itself
        // accepts with no complaint. It is outside the D11 allow-list
        // (the code sets the read preference itself). The registry
        // rejects it at save time. This string stands for a value that a
        // person edited by hand in the secrets file afterward (D10: a
        // second check before the driver).
        val connectionString = "mongodb://127.0.0.1:1/exampledb?readPreference=secondary"

        val (failure, logEvents) = captureLogEvents {
            kotlin.runCatching { reader.pollOnce(target, connectionString) }.exceptionOrNull()
        }

        assertTrue(failure is MongoReadFailedException, "Expected a MongoReadFailedException, got $failure.")
        // Issue #17, decision 4: the scheduler now owns the one WARN of a
        // failed cycle. This line moved to DEBUG, so it never doubles
        // the scheduler's own line.
        val debugLine = logEvents.single { it.loggerName.contains("MongoAppReader") && it.level == Level.DEBUG }
        assertEquals(
            "The MongoDB read failed. IllegalArgumentException",
            debugLine.formattedMessage,
            "The registry check, not a driver connect failure, must reject this string.",
        )
    }

    @Test
    fun `a raw slash in the SRV password gives the fixed sentence, with no marker in the message, the cause chain, or a log line`() = runBlocking {
        val markerUser = "octomarkeruserb3f1"
        val markerSecondValue = "octomarkerpassword9d2e"
        // The unescaped "/" of the password breaks the driver's own
        // parse of the URI (BLOCKER 1 of the security review of pull
        // request #160). ConnectionStringValidator.check lets this
        // text through. A raw "/" inside the user-info part is a gap
        // of that allow-list check, not of this test.
        val connectionString = "mongodb+srv://$markerUser:$markerSecondValue/withslash@cluster0.example.mongodb.net/exampledb"
        val target = PollTarget(appId, "db", "octometer_events", cursor = null)

        val (failure, logEvents) = captureLogEvents {
            kotlin.runCatching { reader.pollOnce(target, connectionString) }.exceptionOrNull()
        }

        assertTrue(failure is MongoReadFailedException, "Expected a MongoReadFailedException, got $failure.")
        // Issue #28, design decision D8: the message now holds the
        // mapped status and the code, never the cause's class name or
        // its message text. An IllegalArgumentException of the
        // registry check maps to no rule of D8, thus it falls to ERROR.
        assertEquals("The reader could not read MongoDB. status=ERROR code=null", failure.message)
        assertNoMarker(failure, markerUser, markerSecondValue)
        logEvents.forEach { event -> assertNoMarkerInText(event.formattedMessage, markerUser, markerSecondValue) }
    }

    // --- The cycle timeout of D6 (MINOR finding, both reviews) ---

    @Test
    fun `the cycle timeout of D6 is pinned at 45 seconds`() {
        assertEquals(45_000L, CYCLE_TIMEOUT_MILLIS)
    }

    @Test
    fun `a page source slower than the cycle timeout trips the timeout`() = runBlocking {
        val shortTimeoutReader = MongoAppReader(eventStore, settleLagSeconds = 2, cycleTimeoutMillis = 50)
        val slowFetch: suspend (String?) -> List<Document> = { _ ->
            delay(500)
            fakePage(1, PAGE_LIMIT)
        }

        val failure = kotlin.runCatching {
            shortTimeoutReader.runCycleWithTimeout(appId, null, MAX_PAGES_PER_CYCLE, PAGE_LIMIT, slowFetch)
        }.exceptionOrNull()

        assertTrue(failure is TimeoutCancellationException, "A page source slower than the cycle timeout must throw. Got $failure.")
    }

    // --- The client cache of clientFor (design decision D10, issue #21) ---
    //
    // These tests call clientFor directly, marked internal for this
    // purpose (the same rule as runCycle, idFilter, and boundObjectId
    // above). [StubMongoClient] replaces the Mockito double of correction
    // round 1 (Kotlin review MINOR 8 of pull request #185). MongoClient
    // is final, but its public constructor takes the reactive-streams
    // MongoClient, an interface. Kotlin delegation (`by`) gives a small
    // double with no Mockito.

    @Test
    fun `clientFor gives the same client back when the connection string is unchanged`() {
        var factoryCalls = 0
        val stub = StubMongoClient("mongodb://127.0.0.1:41001/exampledb")
        val cachingReader = MongoAppReader(eventStore, settleLagSeconds = 1, clientFactory = { _ ->
            factoryCalls += 1
            stub.client
        })

        val first = cachingReader.clientFor(appId, "mongodb://host-a:27017/exampledb")
        val second = cachingReader.clientFor(appId, "mongodb://host-a:27017/exampledb")

        assertEquals(1, factoryCalls, "The same connection string must build the client only one time.")
        assertTrue(first === second, "clientFor must give the kept client back, not a fresh one.")
        assertEquals(0, stub.closeCount, "An unchanged connection string must never close the client.")
        cachingReader.close()
    }

    // The acceptance proof of the maintainer's correction: two calls
    // with two connection strings give two factory calls and one
    // close() of the first client; a third call with the second string
    // gives no new client (design decision D10).
    @Test
    fun `clientFor closes the old client on a changed connection string, and builds one client for each distinct string`() {
        val firstStub = StubMongoClient("mongodb://127.0.0.1:41002/exampledb")
        val secondStub = StubMongoClient("mongodb://127.0.0.1:41003/exampledb")
        val stubs = listOf(firstStub, secondStub)
        var factoryCalls = 0
        val cachingReader = MongoAppReader(eventStore, settleLagSeconds = 1, clientFactory = { _ ->
            stubs[factoryCalls].client.also { factoryCalls += 1 }
        })

        cachingReader.clientFor(appId, "mongodb://host-a:27017/exampledb")
        cachingReader.clientFor(appId, "mongodb://host-b:27017/exampledb")
        cachingReader.clientFor(appId, "mongodb://host-b:27017/exampledb")

        assertEquals(
            2,
            factoryCalls,
            "A changed connection string must build one fresh client; the repeat of the second string must reuse it.",
        )
        assertEquals(1, firstStub.closeCount, "A changed connection string must close the old client.")
        assertEquals(0, secondStub.closeCount, "A repeat of the current string must never close the current client.")
        cachingReader.close()
    }

    // MAJOR 1, security review of pull request #185: a failed client
    // build must leave the old, working client in place. The old form
    // of clientFor closed the old client, then it called the factory.
    // A failed build then left a closed client in the cache, with no
    // fix but a restart of the monitor. This test breaks the second
    // string on purpose, then proves the old client still works.
    @Test
    fun `a failed client build leaves the old client open, and a later working string closes it`() {
        val firstStub = StubMongoClient("mongodb://127.0.0.1:41004/exampledb")
        val fourthStub = StubMongoClient("mongodb://127.0.0.1:41005/exampledb")
        var factoryCalls = 0
        val breakingReader = MongoAppReader(eventStore, settleLagSeconds = 1, clientFactory = { _ ->
            factoryCalls += 1
            when (factoryCalls) {
                1 -> firstStub.client
                2 -> throw IllegalStateException("The second connection string always fails to build in this test.")
                else -> fourthStub.client
            }
        })

        val first = breakingReader.clientFor(appId, "mongodb://host-a:27017/exampledb")
        assertFailsWith<IllegalStateException>("The second connection string must fail to build in this test.") {
            breakingReader.clientFor(appId, "mongodb://host-b:27017/exampledb")
        }

        val third = breakingReader.clientFor(appId, "mongodb://host-a:27017/exampledb")
        assertTrue(third === first, "The third call must reuse the first, still-open client.")
        assertEquals(2, factoryCalls, "The failed build must add no factory call for the reused string.")
        assertEquals(0, firstStub.closeCount, "A failed build of a different string must never close the current client.")

        val fourth = breakingReader.clientFor(appId, "mongodb://host-b:27017/exampledb")
        assertTrue(fourth === fourthStub.client, "A later working string must build a fresh client.")
        assertEquals(1, firstStub.closeCount, "The working fourth call must close the client it replaced.")
        breakingReader.close()
    }

    // Kotlin review MINOR 3 of pull request #185: CachedClient is no
    // longer a data class, and it overrides toString() with fixed
    // text. A data class would print the hash and the client text
    // instead, for example inside a future log line of the whole map.
    @Test
    fun `toString of the cached client entry holds neither the hash nor the client text`() {
        val connectionString = "mongodb://host-tostring:27017/exampledb"
        val stub = StubMongoClient("mongodb://127.0.0.1:41006/exampledb")
        val stringReader = MongoAppReader(eventStore, settleLagSeconds = 1, clientFactory = { stub.client })

        stringReader.clientFor(appId, connectionString)

        val entry = singleCachedClient(stringReader)
        val text = entry.toString()
        assertEquals("CachedClient", text, "The entry must print the fixed text \"CachedClient\".")
        assertFalse(text.contains(sha256HexForTest(connectionString)), "toString() must hold no hash: $text")
        assertFalse(text.contains(stub.client.toString()), "toString() must hold no client text: $text")
        stringReader.close()
    }

    // Security MINOR 1 of pull request #185: the earlier reflection
    // test read one level of the map, with a Mockito double, and
    // marked the host only. StubMongoClient now wraps a real driver
    // client (Kotlin review MINOR 8), so this test can walk into the
    // client's own settings too. It marks the user name, the
    // password, the host, and the port of the connection string.
    //
    // The whole connection string never sits in one field, even
    // there: the security review's own probe found that the driver
    // settings hold the user name, the password, and each host as
    // separate fields, never the one text of the URI. This test
    // therefore checks for the one combined marker text below, not
    // for each of its four parts alone.
    @Test
    fun `no field of the reader, of a cached client entry, or of the client's own settings holds the connection string`() {
        val markerUser = "octomarkeruserc9a2"
        // markerSecondValue, not markerPassword (the form of pull
        // request #160): gitleaks' generic-api-key rule matches an
        // identifier that names a secret, beside a string literal.
        val markerSecondValue = "octomarkerpassc9a2"
        val markerHost = "octomarkerhostc9a2.invalid"
        val markerPort = 48213
        val markedConnectionString = "mongodb://$markerUser:$markerSecondValue@$markerHost:$markerPort/exampledb"
        val reflectingReader = MongoAppReader(
            eventStore,
            settleLagSeconds = 1,
            clientFactory = { connectionString -> StubMongoClient(connectionString).client },
        )

        reflectingReader.clientFor(appId, markedConnectionString)

        assertNoMarkerInGraph(reflectingReader, markedConnectionString)
        reflectingReader.close()
    }

    private fun singleCachedClient(reader: MongoAppReader): Any {
        val field = reader.javaClass.getDeclaredField("clients")
        field.isAccessible = true
        val map = field.get(reader) as Map<*, *>
        return map.values.single()!!
    }

    private fun sha256HexForTest(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(text.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { byte -> "%02x".format(byte) }
    }

    /**
     * Walks the whole object graph from [root], through every
     * declared field, and fails when a `String` or `char[]` field
     * holds [marker]. It stops at the boundary of this module and of
     * the MongoDB driver (a package name that starts with neither
     * `octometer.monitor.mongo` nor `com.mongodb`), so it never walks
     * into the JVM, Netty, or SQLite. An [IdentityHashMap]-backed set
     * guards against a cycle in the driver's own object graph.
     */
    private fun assertNoMarkerInGraph(root: Any, marker: String) {
        val visited: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
        walkForMarker(root, marker, visited, depth = 0)
    }

    private fun walkForMarker(value: Any?, marker: String, visited: MutableSet<Any>, depth: Int) {
        if (value == null || depth > 24) return
        if (value is String) {
            assertFalse(value.contains(marker), "A String field must hold no connection string: $value")
            return
        }
        if (value is CharArray) {
            assertFalse(String(value).contains(marker), "A char[] field must hold no connection string.")
            return
        }
        if (value is Number || value is Boolean || value is Char) return
        if (!visited.add(value)) return
        when (value) {
            is Map<*, *> -> value.values.forEach { walkForMarker(it, marker, visited, depth + 1) }
            is Collection<*> -> value.forEach { walkForMarker(it, marker, visited, depth + 1) }
            is Array<*> -> value.forEach { walkForMarker(it, marker, visited, depth + 1) }
        }
        val packageName = value.javaClass.`package`?.name ?: ""
        if (!packageName.startsWith("octometer.monitor.mongo") && !packageName.startsWith("com.mongodb")) return
        var currentClass: Class<*>? = value.javaClass
        while (currentClass != null && currentClass != Any::class.java) {
            for (field in currentClass.declaredFields) {
                if (field.isSynthetic) continue
                field.isAccessible = true
                val fieldValue = runCatching { field.get(value) }.getOrNull()
                walkForMarker(fieldValue, marker, visited, depth + 1)
            }
            currentClass = currentClass.superclass
        }
    }

    private fun fakePage(pageIndex: Int, size: Int): List<Document> =
        (1..size).map { itemIndex ->
            Document("_id", ObjectId())
                .append("ts", Date(1_700_000_000_000L))
                .append("element", "e-$pageIndex-$itemIndex")
                .append("sessionId", "session-1")
                .append("userId", "user-1")
        }

    private fun goodDocument(element: String): Document =
        Document("_id", ObjectId())
            .append("ts", Date(1_700_000_000_000L))
            .append("element", element)
            .append("sessionId", "session-1")
            .append("userId", "user-1")

    private fun assertNoMarker(throwable: Throwable, vararg markers: String) {
        var current: Throwable? = throwable
        val seen = mutableSetOf<Throwable>()
        while (current != null && seen.add(current)) {
            assertNoMarkerInText(current.toString(), *markers)
            assertNoMarkerInText(current.message ?: "", *markers)
            current.stackTrace.forEach { frame -> assertNoMarkerInText(frame.toString(), *markers) }
            current = current.cause
        }
    }

    private fun assertNoMarkerInText(text: String, vararg markers: String) {
        markers.forEach { marker ->
            assertFalse(text.contains(marker), "The text must hold no marker ($marker): $text")
        }
    }
}

/**
 * A small double of [MongoClient] (Kotlin review MINOR 8 of pull
 * request #185, in place of Mockito). [MongoClient] is a final class,
 * but its public constructor takes [ReactiveMongoClient], an
 * interface. Kotlin delegation (`by`) gives a double with no Mockito.
 * The [real] client below never opens a socket at construction; the
 * driver connects only when a caller runs a command.
 */
private class StubMongoClient(connectionString: String) {
    private val real = ReactiveMongoClients.create(connectionString)

    var closeCount = 0
        private set

    val client: MongoClient = MongoClient(
        object : ReactiveMongoClient by real {
            override fun close() {
                closeCount += 1
                real.close()
            }
        },
    )
}

private fun shortTimeoutClient(connectionString: String): com.mongodb.kotlin.client.coroutine.MongoClient {
    val settings = com.mongodb.MongoClientSettings.builder()
        .applyConnectionString(com.mongodb.ConnectionString(connectionString))
        .applyToClusterSettings { cluster -> cluster.serverSelectionTimeout(300, java.util.concurrent.TimeUnit.MILLISECONDS) }
        .build()
    return com.mongodb.kotlin.client.coroutine.MongoClient.create(settings)
}

private suspend fun insertApp(database: SqliteDatabase, name: String): Long =
    database.write { writer ->
        writer.prepareStatement(
            "INSERT INTO app (name, database_name, collection_name, created_at) VALUES (?, ?, ?, ?)",
        ).use { insert ->
            insert.setString(1, name)
            insert.setString(2, "db")
            insert.setString(3, "octometer_events")
            insert.setLong(4, 1_700_000_000_000L)
            insert.executeUpdate()
        }
        writer.createStatement().use { statement ->
            statement.executeQuery("SELECT last_insert_rowid()").use { result ->
                result.next()
                result.getLong(1)
            }
        }
    }

private suspend fun countEvents(database: SqliteDatabase, appId: Long): Int =
    database.read { reader ->
        reader.prepareStatement("SELECT COUNT(*) FROM event WHERE app_id = ?").use { select ->
            select.setLong(1, appId)
            select.executeQuery().use { result ->
                result.next()
                result.getInt(1)
            }
        }
    }

/** Writes one fixed cursor value into the app row, for the guard test of issue #187. */
private suspend fun setCursor(database: SqliteDatabase, appId: Long, cursor: String) {
    database.write { writer ->
        writer.prepareStatement("UPDATE app SET cursor = ? WHERE id = ?").use { update ->
            update.setString(1, cursor)
            update.setLong(2, appId)
            update.executeUpdate()
        }
    }
}

/** Mimics `AppRegistryService.resetPollState` for the one column that this reader guards. */
private suspend fun resetCursor(database: SqliteDatabase, appId: Long) {
    database.write { writer ->
        writer.prepareStatement("UPDATE app SET cursor = NULL WHERE id = ?").use { update ->
            update.setLong(1, appId)
            update.executeUpdate()
        }
    }
}

private suspend fun readCursor(database: SqliteDatabase, appId: Long): String? =
    database.read { reader ->
        reader.prepareStatement("SELECT cursor FROM app WHERE id = ?").use { select ->
            select.setLong(1, appId)
            select.executeQuery().use { result ->
                result.next()
                result.getString(1)
            }
        }
    }

/** Reads the reason of one `skipped_event` row (issue #27, D5). */
private suspend fun readSkippedReason(database: SqliteDatabase, appId: Long, eventId: String): String? =
    database.read { reader ->
        reader.prepareStatement("SELECT reason FROM skipped_event WHERE app_id = ? AND event_id = ?").use { select ->
            select.setLong(1, appId)
            select.setString(2, eventId)
            select.executeQuery().use { result ->
                if (result.next()) result.getString(1) else null
            }
        }
    }

/** The three new columns of one `event` row (issue #110). */
private data class EventColumns(val path: String?, val referrerHost: String?, val kind: Int)

private suspend fun readEventColumns(database: SqliteDatabase, appId: Long, eventId: String): EventColumns =
    database.read { reader ->
        reader.prepareStatement(
            "SELECT path, referrer_host, kind FROM event WHERE app_id = ? AND event_id = ?",
        ).use { select ->
            select.setLong(1, appId)
            select.setString(2, eventId)
            select.executeQuery().use { result ->
                result.next()
                EventColumns(
                    path = result.getString(1),
                    referrerHost = result.getString(2),
                    kind = result.getInt(3),
                )
            }
        }
    }
