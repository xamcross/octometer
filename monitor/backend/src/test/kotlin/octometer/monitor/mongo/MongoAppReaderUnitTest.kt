package octometer.monitor.mongo

import ch.qos.logback.classic.Level
import com.mongodb.kotlin.client.coroutine.MongoClient
import java.io.File
import java.nio.file.Files
import java.util.Date
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
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
        // The unescaped "/" of the password breaks the driver's own parse
        // of the URI (BLOCKER 1 of the security review of pull request
        // #160). ConnectionStringValidator.check lets this text through,
        // because a raw "/" inside the user-info part is a gap of that
        // allow-list check, not of this test.
        val connectionString = "mongodb+srv://$markerUser:$markerSecondValue/withslash@cluster0.example.mongodb.net/exampledb"
        val target = PollTarget(appId, "db", "octometer_events", cursor = null)

        val (failure, logEvents) = captureLogEvents {
            kotlin.runCatching { reader.pollOnce(target, connectionString) }.exceptionOrNull()
        }

        assertTrue(failure is MongoReadFailedException, "Expected a MongoReadFailedException, got $failure.")
        assertEquals("The reader could not read MongoDB. IllegalArgumentException", failure.message)
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
    // above). pollOnce also calls the hello command and the find
    // command of a real MongoDatabase; a Mockito double of that chain
    // would prove nothing beyond what clientFor already proves on its
    // own, at the cost of a large, fragile stub of the driver. Mockito
    // mocks MongoClient directly: it is a final class, but Mockito 5
    // mocks a final class with no extra mock maker file (its inline
    // mock maker is the default since 5.0.0).

    @Test
    fun `clientFor gives the same client back when the connection string is unchanged`() {
        val client = mock(MongoClient::class.java)
        var factoryCalls = 0
        val cachingReader = MongoAppReader(eventStore, settleLagSeconds = 1, clientFactory = { _ ->
            factoryCalls += 1
            client
        })

        val first = cachingReader.clientFor(appId, "mongodb://host-a:27017/exampledb")
        val second = cachingReader.clientFor(appId, "mongodb://host-a:27017/exampledb")

        assertEquals(1, factoryCalls, "The same connection string must build the client only one time.")
        assertTrue(first === second, "clientFor must give the kept client back, not a fresh one.")
        verify(client, never()).close()
        cachingReader.close()
    }

    // The acceptance proof of the maintainer's correction: two calls
    // with two connection strings give two factory calls and one
    // close() of the first client; a third call with the second string
    // gives no new client (design decision D10).
    @Test
    fun `clientFor closes the old client on a changed connection string, and builds one client for each distinct string`() {
        val firstClient = mock(MongoClient::class.java)
        val secondClient = mock(MongoClient::class.java)
        val builtClients = listOf(firstClient, secondClient)
        var factoryCalls = 0
        val cachingReader = MongoAppReader(eventStore, settleLagSeconds = 1, clientFactory = { _ ->
            builtClients[factoryCalls].also { factoryCalls += 1 }
        })

        cachingReader.clientFor(appId, "mongodb://host-a:27017/exampledb")
        cachingReader.clientFor(appId, "mongodb://host-b:27017/exampledb")
        cachingReader.clientFor(appId, "mongodb://host-b:27017/exampledb")

        assertEquals(
            2,
            factoryCalls,
            "A changed connection string must build one fresh client; the repeat of the second string must reuse it.",
        )
        verify(firstClient).close()
        verify(secondClient, never()).close()
        cachingReader.close()
    }

    // Design decision D11 (BLOCKER 1 of the security review of issue
    // #16): a connection string never sits in a field. This reads every
    // declared field of the reader, and of each cached client entry,
    // through reflection, and it asserts that no string field holds the
    // marker of the connection string that clientFor just built a
    // client from.
    @Test
    fun `no field of the reader or of a cached client entry holds the connection string after clientFor runs`() {
        val marker = "octomarkerconnstringc9a2"
        val markedConnectionString = "mongodb://$marker-host:27017/exampledb?tls=true"
        val client = mock(MongoClient::class.java)
        val reflectingReader = MongoAppReader(eventStore, settleLagSeconds = 1, clientFactory = { client })

        reflectingReader.clientFor(appId, markedConnectionString)

        assertNoMarkerField(reflectingReader, marker)
        reflectingReader.close()
    }

    private fun assertNoMarkerField(reader: MongoAppReader, marker: String) {
        for (field in reader.javaClass.declaredFields) {
            field.isAccessible = true
            val value = field.get(reader)
            assertFieldHoldsNoMarker(field.name, value, marker)
            if (value is Map<*, *>) {
                for (entry in value.values) {
                    if (entry == null) continue
                    for (entryField in entry.javaClass.declaredFields) {
                        entryField.isAccessible = true
                        assertFieldHoldsNoMarker(
                            "${field.name}.${entryField.name}",
                            entryField.get(entry),
                            marker,
                        )
                    }
                }
            }
        }
    }

    private fun assertFieldHoldsNoMarker(fieldName: String, value: Any?, marker: String) {
        if (value is String) {
            assertFalse(value.contains(marker), "The field $fieldName must hold no connection string.")
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
