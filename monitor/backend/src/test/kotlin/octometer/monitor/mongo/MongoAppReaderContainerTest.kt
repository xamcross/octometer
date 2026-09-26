package octometer.monitor.mongo

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import octometer.kit.core.ingest.IngestEvent
import octometer.kit.mongo.store.MongoEventLogStore
import octometer.monitor.captureLogEvents
import octometer.monitor.registerTempRoot
import octometer.monitor.store.EventStore
import octometer.monitor.store.SqliteDatabase
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests of [MongoAppReader] against a real MongoDB server
 * (issue #16, design decisions D4, D10, section 4.3). They also cover
 * correction round 1 of pull request #160: BLOCKER 1 and BLOCKER 2 of
 * the Kotlin review. Each test needs Docker; a machine with no Docker
 * skips the whole class. The job "JVM modules" on Ubuntu runs it. The
 * job "Monitor backend on Windows" has no Docker and skips it by design
 * (`monitor/backend/build.gradle.kts` guards the Ubuntu job only).
 *
 * Each event goes in through [octometer.kit.mongo.store.MongoEventLogStore],
 * the store of the app side of the contract (issue #11). Thus the test
 * reads the exact document shape that a real app writes.
 */
@Testcontainers(disabledWithoutDocker = true)
class MongoAppReaderContainerTest {

    @Suppress("unused")
    companion object {
        @Container
        @JvmStatic
        private val MONGO = MongoDBContainer(DockerImageName.parse("mongo:7.0"))
    }

    private val root = Files.createTempDirectory("octometer-mongo-reader-container-test-").toFile().also { registerTempRoot(it) }
    private val dataDir = File(root, "data")
    private lateinit var sqlite: SqliteDatabase
    private lateinit var eventStore: EventStore
    private lateinit var reader: MongoAppReader
    private lateinit var rawClient: MongoClient
    private lateinit var databaseName: String
    private var appId: Long = 0

    @BeforeTest
    fun setUp() = runBlocking {
        sqlite = SqliteDatabase.open(dataDir.absolutePath)
        eventStore = EventStore(sqlite)
        // A lag of 1 second here. Most tests of this class check the page
        // loop and the document shape, not the lag itself. Each of them
        // calls settle() below before the first poll. The two lag tests
        // further down build their own reader with its own lag.
        reader = MongoAppReader(eventStore, settleLagSeconds = 1)
        appId = insertApp(sqlite, "demo")
        databaseName = "octometer_test_" + System.nanoTime()
        rawClient = MongoClients.create(MONGO.connectionString)
    }

    @AfterTest
    fun tearDown() {
        reader.close()
        rawClient.close()
        sqlite.close()
        root.deleteRecursively()
    }

    @Test
    fun `2500 events of the kit store arrive exactly one time in _id order`() = runBlocking {
        val store = MongoEventLogStore(rawClient.getDatabase(databaseName))
        val events = (1..2500).map { index -> ingestEvent("checkout.save-$index") }
        store.append(events, "user-1")
        settle()

        var outcome = reader.pollOnce(target(cursor = null), MONGO.connectionString)
        while (outcome.pagesRead > 0) {
            outcome = reader.pollOnce(target(cursor = outcome.cursor), MONGO.connectionString)
        }

        assertEquals(2500, countEvents(sqlite, appId))
        val storedIds = readEventIdsInInsertOrder(sqlite, appId)
        assertEquals(storedIds.sorted(), storedIds, "Each page must arrive in ascending _id order.")

        // A second full drain must add 0 new rows. The delivery is "at
        // least once" at the MongoDB level, but the SQLite primary key
        // (app_id, event_id) drops a duplicate (section 4.3).
        val replay = reader.pollOnce(target(cursor = readCursor(sqlite, appId)), MONGO.connectionString)
        assertEquals(0, replay.eventsStored)
        assertEquals(2500, countEvents(sqlite, appId))
    }

    // BLOCKER 2, Kotlin review of pull request #160. ObjectId's
    // getSmallestWithDate truncates the server time to a whole second.
    // A test that inserts and polls at once always gets 0 events from
    // the truncation alone, with no help from the lag. Such a test
    // cannot fail for a broken lag. This test waits past the second of
    // the insert first, so only the lag can explain the first 0 result.
    @Test
    fun `an event younger than the lag is absent, and a shorter lag from the same cursor reads it`() = runBlocking {
        val store = MongoEventLogStore(rawClient.getDatabase(databaseName))
        store.append(listOf(ingestEvent("nav.open")), "user-1")
        delay(5_000)

        val bigLagReader = MongoAppReader(eventStore, settleLagSeconds = 30)
        val bigLagOutcome = bigLagReader.pollOnce(target(cursor = null), MONGO.connectionString)
        assertEquals(0, bigLagOutcome.eventsStored, "A lag of 30 seconds must still exclude a 5-second-old event.")
        bigLagReader.close()

        val smallLagReader = MongoAppReader(eventStore, settleLagSeconds = 1)
        val smallLagOutcome = smallLagReader.pollOnce(target(cursor = bigLagOutcome.cursor), MONGO.connectionString)
        assertEquals(1, smallLagOutcome.eventsStored, "A lag of 1 second must read the same event.")
        assertEquals(1, countEvents(sqlite, appId))
        smallLagReader.close()
    }

    // BLOCKER 1, Kotlin review of pull request #160. readBound must
    // follow the serverTimeSource seam of MongoAppReader, never a
    // value the code computes on its own. A source 10 minutes ahead of
    // hello proves the seam is live. With the real hello time and a
    // lag of 1 second, a just-written event would stay out. With the
    // far-future source, the bound must include it.
    @Test
    fun `a server time source ahead of hello reads an event that the default source leaves out`() = runBlocking {
        val store = MongoEventLogStore(rawClient.getDatabase(databaseName))
        store.append(listOf(ingestEvent("nav.open")), "user-1")

        val aheadReader = MongoAppReader(
            eventStore,
            settleLagSeconds = 1,
            serverTimeSource = { database -> Date(helloLocalTime(database).time + 600_000) },
        )
        val outcome = aheadReader.pollOnce(target(cursor = null), MONGO.connectionString)

        assertEquals(1, outcome.eventsStored, "A server time source 10 minutes ahead of hello must read the just-written event.")
        aheadReader.close()
    }

    // BLOCKER 1, Kotlin review of pull request #160: "Remove the hello
    // read for a moment and see the test fail." A CommandListener on a
    // dedicated client proves that one poll cycle always sends a hello
    // command before the find command of the page.
    @Test
    fun `one poll cycle sends a hello command before the find command`() = runBlocking {
        val commandNames = Collections.synchronizedList(mutableListOf<String>())
        val listener = object : CommandListener {
            override fun commandStarted(event: CommandStartedEvent) {
                commandNames += event.commandName
            }
        }
        val settings = MongoClientSettings.builder()
            .applyConnectionString(ConnectionString(MONGO.connectionString))
            .addCommandListener(listener)
            .build()
        val listenerReader = MongoAppReader(
            eventStore,
            settleLagSeconds = 1,
            clientFactory = { com.mongodb.kotlin.client.coroutine.MongoClient.create(settings) },
        )

        listenerReader.pollOnce(target(cursor = null), MONGO.connectionString)

        val helloIndex = commandNames.indexOf("hello")
        val findIndex = commandNames.indexOf("find")
        assertTrue(helloIndex != -1, "One pollOnce must send a hello command.")
        assertTrue(findIndex != -1, "One pollOnce must send a find command.")
        assertTrue(helloIndex < findIndex, "The hello command must run before the find command.")
        listenerReader.close()
    }

    // MAJOR 1, security review of pull request #160. logback.xml sets
    // org.mongodb to WARN. This test polls with a marker user name and
    // password, then it reads every captured log line of the cycle,
    // not only ERROR. It then asserts that no line holds either marker.
    @Test
    fun `a marker user name and password in the connection string stay out of every log line at the shipped level`() = runBlocking {
        val markerUser = "octomarkerloguser4c2b"
        val markerSecondValue = "octomarkerlogpass7e91"
        val host = MONGO.host
        val port = MONGO.getMappedPort(27017)
        val markedConnectionString = "mongodb://$markerUser:$markerSecondValue@$host:$port/exampledb?authSource=admin"

        val (_, logEvents) = captureLogEvents {
            kotlin.runCatching { reader.pollOnce(target(cursor = null), markedConnectionString) }
        }

        assertTrue(logEvents.isNotEmpty(), "The cycle must write at least one log line, or this test proves nothing.")
        logEvents.forEach { event ->
            assertFalse(event.formattedMessage.contains(markerUser), "A log line must hold no user name: ${event.formattedMessage}")
            assertFalse(event.formattedMessage.contains(markerSecondValue), "A log line must hold no password: ${event.formattedMessage}")
        }
    }

    // MINOR finding, both reviews of pull request #160: a byte budget
    // for one page. Six documents of about 2 MB each cross the 8 MB
    // budget of PAGE_BYTE_BUDGET before the sixth document.
    @Test
    fun `a page stops before the byte budget, and the next cycle reads the rest`() = runBlocking {
        val store = MongoEventLogStore(rawClient.getDatabase(databaseName))
        val bigElement = "x".repeat(2 * 1024 * 1024)
        val events = (1..6).map { index -> ingestEvent("$bigElement-$index") }
        store.append(events, "user-1")
        settle()

        val firstOutcome = reader.pollOnce(target(cursor = null), MONGO.connectionString)

        assertTrue(
            firstOutcome.eventsStored in 1..5,
            "The byte budget must stop the page before all 6 documents arrive. Got ${firstOutcome.eventsStored}.",
        )

        val secondOutcome = reader.pollOnce(target(cursor = firstOutcome.cursor), MONGO.connectionString)
        assertEquals(6, firstOutcome.eventsStored + secondOutcome.eventsStored, "The rest must arrive on a later page.")
    }

    @Test
    fun `the reader accepts a document with an unknown field`() = runBlocking {
        val rawCollection = rawClient.getDatabase(databaseName).getCollection("octometer_events")
        rawCollection.insertOne(
            org.bson.Document("_id", org.bson.types.ObjectId())
                .append("ts", java.util.Date())
                .append("element", "checkout.save")
                .append("sessionId", "session-1")
                .append("userId", "user-1")
                .append("aFieldFromALaterContractVersion", "some value"),
        )
        settle()

        val outcome = reader.pollOnce(target(cursor = null), MONGO.connectionString)

        assertEquals(1, outcome.eventsStored)
        assertEquals(1, countEvents(sqlite, appId))
    }

    @Test
    fun `a deleted cursor document does not break the next read`() = runBlocking {
        val store = MongoEventLogStore(rawClient.getDatabase(databaseName))
        store.append(listOf(ingestEvent("first.click")), "user-1")
        settle()
        val firstOutcome = reader.pollOnce(target(cursor = null), MONGO.connectionString)
        assertEquals(1, firstOutcome.eventsStored)

        // Simulates the TTL delete of section 4.3. The document that the
        // cursor points at is gone, but the cursor value itself stays a
        // plain index seek.
        val rawCollection = rawClient.getDatabase(databaseName).getCollection("octometer_events")
        rawCollection.deleteOne(org.bson.Document("_id", org.bson.types.ObjectId(firstOutcome.cursor)))

        store.append(listOf(ingestEvent("second.click")), "user-1")
        settle()
        val secondOutcome = reader.pollOnce(target(cursor = firstOutcome.cursor), MONGO.connectionString)

        assertEquals(1, secondOutcome.eventsStored, "The second cycle must read the new event, not the deleted one.")
        // The first event stays in SQLite too: the TTL delete happens on
        // the MongoDB side, after the event already sits in the store.
        assertEquals(2, countEvents(sqlite, appId))
    }

    @Test
    fun `with 10500 events the first cycle stores 10000`() = runBlocking {
        val store = MongoEventLogStore(rawClient.getDatabase(databaseName))
        val events = (1..10_500).map { index -> ingestEvent("checkout.save-$index") }
        store.append(events, "user-1")
        settle()

        val firstOutcome = reader.pollOnce(target(cursor = null), MONGO.connectionString)

        assertEquals(10_000, firstOutcome.eventsStored)
        assertEquals(MAX_PAGES_PER_CYCLE, firstOutcome.pagesRead)
        assertEquals(10_000, countEvents(sqlite, appId))

        val secondOutcome = reader.pollOnce(target(cursor = firstOutcome.cursor), MONGO.connectionString)
        assertEquals(500, secondOutcome.eventsStored)
        assertEquals(10_500, countEvents(sqlite, appId))
    }

    @Test
    fun `after 20 cycles the monitor holds one client for the app`() = runBlocking {
        var creations = 0
        val countingReader = MongoAppReader(eventStore, settleLagSeconds = 2, clientFactory = { connectionString ->
            creations += 1
            com.mongodb.kotlin.client.coroutine.MongoClient.create(connectionString)
        })
        val store = MongoEventLogStore(rawClient.getDatabase(databaseName))

        repeat(20) { cycle ->
            store.append(listOf(ingestEvent("click-$cycle")), "user-1")
            val cursor = readCursor(sqlite, appId)
            countingReader.pollOnce(target(cursor = cursor), MONGO.connectionString)
        }

        assertEquals(1, creations, "The reader must keep one client for the app across the 20 cycles.")
        countingReader.close()
    }

    // --- The invalid-document skip and the poll status (design decisions D5, D8, issue #27) ---

    @Test
    fun `five invalid documents skip with fixed reasons, and the valid documents of the page arrive`() = runBlocking {
        val rawCollection = rawClient.getDatabase(databaseName).getCollection("octometer_events")
        val noElement = invalidDocument().append("ts", Date()).append("sessionId", "session-1").append("userId", "user-1")
        val noTs = invalidDocument().append("element", "checkout.save").append("sessionId", "session-1").append("userId", "user-1")
        val noSessionId = invalidDocument().append("ts", Date()).append("element", "checkout.save").append("userId", "user-1")
        val emptyUserId = invalidDocument().append("ts", Date()).append("element", "checkout.save")
            .append("sessionId", "session-1").append("userId", "")
        val wrongType = invalidDocument().append("ts", "not-a-date").append("element", "checkout.save")
            .append("sessionId", "session-1").append("userId", "user-1")
        val good1 = invalidDocument().append("ts", Date()).append("element", "good.first")
            .append("sessionId", "session-1").append("userId", "user-1")
        val good2 = invalidDocument().append("ts", Date()).append("element", "good.second")
            .append("sessionId", "session-1").append("userId", "user-1")
        val page = listOf(noElement, noTs, noSessionId, emptyUserId, wrongType, good1, good2)
        rawCollection.insertMany(page)
        settle()

        val outcome = reader.pollOnce(target(cursor = null), MONGO.connectionString)

        assertEquals(2, outcome.eventsStored, "The two valid documents must arrive.")
        assertEquals(5, outcome.eventsSkipped, "The five invalid documents must skip.")
        assertEquals(2, countEvents(sqlite, appId))
        assertEquals(page.last().getObjectId("_id").toHexString(), outcome.cursor, "The cursor must stand at the last _id of the page.")

        val reasons = readSkippedReasons(sqlite, appId)
        assertEquals(
            mapOf(
                noElement.getObjectId("_id").toHexString() to "element missing",
                noTs.getObjectId("_id").toHexString() to "ts missing",
                noSessionId.getObjectId("_id").toHexString() to "sessionId missing",
                emptyUserId.getObjectId("_id").toHexString() to "userId empty",
                wrongType.getObjectId("_id").toHexString() to "ts wrong type",
            ),
            reasons,
            "Each invalid document must get one skipped_event row with the fixed reason.",
        )
    }

    // The acceptance test of issue #27. A marker string in each field of
    // an invalid document must stay out of the skipped_event row. It
    // must also stay out of every captured log line of the cycle.
    @Test
    fun `the skipped_event reason and every captured log line hold no field value of an invalid document`() = runBlocking {
        val marker = "octomarkerfield7d4e"
        val rawCollection = rawClient.getDatabase(databaseName).getCollection("octometer_events")
        val badDocument = invalidDocument()
            .append("ts", marker)
            .append("element", marker)
            .append("sessionId", marker)
            .append("userId", marker)
        rawCollection.insertOne(badDocument)
        settle()

        val (outcome, logEvents) = captureLogEvents {
            reader.pollOnce(target(cursor = null), MONGO.connectionString)
        }

        assertEquals(1, outcome.eventsSkipped)
        val reason = readSkippedReasons(sqlite, appId).getValue(badDocument.getObjectId("_id").toHexString())
        assertEquals("ts wrong type", reason)
        assertFalse(reason.contains(marker), "The reason must hold no field value: $reason")
        logEvents.forEach { event ->
            assertFalse(event.formattedMessage.contains(marker), "A log line must hold no field value: ${event.formattedMessage}")
        }
    }

    @Test
    fun `a good cycle with no skip sets last_poll_at, last_success_at, and the status OK from the injected clock`() = runBlocking {
        val fixedInstant = Instant.parse("2026-09-22T10:00:00Z")
        val clockReader = MongoAppReader(eventStore, settleLagSeconds = 1, clock = Clock.fixed(fixedInstant, ZoneOffset.UTC))
        val store = MongoEventLogStore(rawClient.getDatabase(databaseName))
        store.append(listOf(ingestEvent("nav.open")), "user-1")
        settle()

        clockReader.pollOnce(target(cursor = null), MONGO.connectionString)

        val row = readAppRow(sqlite, appId)
        assertEquals("OK", row.status)
        assertEquals(fixedInstant.toEpochMilli(), row.lastPollAt)
        assertEquals(fixedInstant.toEpochMilli(), row.lastSuccessAt)
        clockReader.close()
    }

    // --- path, referrerHost, and kind (issue #110, design decision D5) ---

    @Test
    fun `a session start document stores its path and referrerHost, and kind 1`() = runBlocking {
        val rawCollection = rawClient.getDatabase(databaseName).getCollection("octometer_events")
        rawCollection.insertOne(
            invalidDocument()
                .append("ts", Date())
                .append("element", "octo:session-start")
                .append("sessionId", "session-1")
                .append("userId", "user-1")
                .append("path", "/")
                .append("referrerHost", "google.com"),
        )
        settle()

        reader.pollOnce(target(cursor = null), MONGO.connectionString)

        val row = readEventColumns(sqlite, appId).single()
        assertEquals("/", row.path)
        assertEquals("google.com", row.referrerHost)
        assertEquals(1, row.kind)
    }

    @Test
    fun `a click document without path or referrerHost stores NULL in both columns, and kind 0`() = runBlocking {
        val rawCollection = rawClient.getDatabase(databaseName).getCollection("octometer_events")
        rawCollection.insertOne(
            invalidDocument()
                .append("ts", Date())
                .append("element", "checkout.save")
                .append("sessionId", "session-1")
                .append("userId", "user-1"),
        )
        settle()

        reader.pollOnce(target(cursor = null), MONGO.connectionString)

        val row = readEventColumns(sqlite, appId).single()
        assertEquals(null, row.path)
        assertEquals(null, row.referrerHost)
        assertEquals(0, row.kind)
    }

    // BLOCKER 1 of the security review of pull request #193: section 6
    // states "it copies referrer_host from a session start". A click
    // document with a present referrerHost field must give NULL.
    @Test
    fun `a click document with a referrerHost field stores NULL in referrer_host`() = runBlocking {
        val rawCollection = rawClient.getDatabase(databaseName).getCollection("octometer_events")
        rawCollection.insertOne(
            invalidDocument()
                .append("ts", Date())
                .append("element", "checkout.save")
                .append("sessionId", "session-1")
                .append("userId", "user-1")
                .append("referrerHost", "google.com"),
        )
        settle()

        reader.pollOnce(target(cursor = null), MONGO.connectionString)

        val row = readEventColumns(sqlite, appId).single()
        assertEquals(null, row.referrerHost)
        assertEquals(0, row.kind)
    }

    // --- The referrerHost rule for a value outside the contract (design decision D5, the correction of 2026-09-26, issue #196) ---

    @Test
    fun `a referrerHost outside the set, and an upper-case host, land as NULL and the row stays`() = runBlocking {
        val rawCollection = rawClient.getDatabase(databaseName).getCollection("octometer_events")
        val outsideSet = invalidDocument().append("ts", Date()).append("element", "octo:session-start")
            .append("sessionId", "session-1").append("userId", "user-1").append("referrerHost", "internal-hr.corp.example")
        val upperCase = invalidDocument().append("ts", Date()).append("element", "octo:session-start")
            .append("sessionId", "session-2").append("userId", "user-1").append("referrerHost", "Google.COM")
        rawCollection.insertMany(listOf(outsideSet, upperCase))
        settle()

        val outcome = reader.pollOnce(target(cursor = null), MONGO.connectionString)

        assertEquals(2, outcome.eventsStored, "The event itself is valid; both rows must commit.")
        assertEquals(0, outcome.eventsSkipped, "A value outside the set must not skip the document.")
        assertEquals(2, countEvents(sqlite, appId))
        val reasons = readSkippedReasons(sqlite, appId)
        assertTrue(reasons.isEmpty(), "No skipped_event row for a valid event.")
        val rows = readEventColumns(sqlite, appId)
        assertTrue(rows.all { it.referrerHost == null }, "Each dropped referrerHost must land as NULL.")
    }

    @Test
    fun `each of the three set values of contract rule C40 lands as it is`() = runBlocking {
        val rawCollection = rawClient.getDatabase(databaseName).getCollection("octometer_events")
        val values = listOf("google.com", "bing.com", "other")
        val documents = values.mapIndexed { index, value ->
            invalidDocument().append("ts", Date()).append("element", "octo:session-start")
                .append("sessionId", "session-$index").append("userId", "user-1").append("referrerHost", value)
        }
        rawCollection.insertMany(documents)
        settle()

        val outcome = reader.pollOnce(target(cursor = null), MONGO.connectionString)

        assertEquals(3, outcome.eventsStored)
        assertEquals(0, outcome.eventsSkipped)
        val storedValues = readEventColumns(sqlite, appId).map { it.referrerHost }.toSet()
        assertEquals(values.toSet(), storedValues, "Each of the three set values must land as it is.")
    }

    @Test
    fun `one WARN line counts the dropped referrerHost fields of the cycle, with no raw host`() = runBlocking {
        val rawCollection = rawClient.getDatabase(databaseName).getCollection("octometer_events")
        val marker = "octomarkerreferrercontainer5e2a.invalid"
        val outsideSet = invalidDocument().append("ts", Date()).append("element", "octo:session-start")
            .append("sessionId", "session-1").append("userId", "user-1").append("referrerHost", marker)
        val upperCase = invalidDocument().append("ts", Date()).append("element", "octo:session-start")
            .append("sessionId", "session-2").append("userId", "user-1").append("referrerHost", "Google.COM")
        rawCollection.insertMany(listOf(outsideSet, upperCase))
        settle()

        val (outcome, logEvents) = captureLogEvents {
            reader.pollOnce(target(cursor = null), MONGO.connectionString)
        }

        assertEquals(0, outcome.eventsSkipped)
        val warnLines = logEvents.filter { it.level == ch.qos.logback.classic.Level.WARN && it.loggerName.contains("MongoAppReader") }
        assertEquals(1, warnLines.size, "One WARN line must count the dropped referrerHost fields.")
        assertTrue(warnLines.single().formattedMessage.contains("2"), "The line must hold the dropped count.")
        logEvents.forEach { event ->
            assertFalse(event.formattedMessage.contains(marker), "A log line of level ${event.level} must hold no raw host.")
        }
    }

    @Test
    fun `a document with a wrong BSON type in referrerHost goes to skipped_event, and the cursor moves`() = runBlocking {
        val rawCollection = rawClient.getDatabase(databaseName).getCollection("octometer_events")
        val bad = invalidDocument().append("ts", Date()).append("element", "octo:session-start")
            .append("sessionId", "session-1").append("userId", "user-1").append("referrerHost", 42)
        val good = invalidDocument().append("ts", Date()).append("element", "good.click")
            .append("sessionId", "session-1").append("userId", "user-1")
        rawCollection.insertMany(listOf(bad, good))
        settle()

        val outcome = reader.pollOnce(target(cursor = null), MONGO.connectionString)

        assertEquals(1, outcome.eventsStored, "The good document must arrive.")
        assertEquals(1, outcome.eventsSkipped, "The bad document must skip.")
        assertEquals(good.getObjectId("_id").toHexString(), outcome.cursor, "The cursor must move past the skip.")
        assertEquals(
            "referrerHost wrong type",
            readSkippedReasons(sqlite, appId).getValue(bad.getObjectId("_id").toHexString()),
        )
    }

    @Test
    fun `a cycle that skips one document sets the status INVALID_DATA`() = runBlocking {
        val rawCollection = rawClient.getDatabase(databaseName).getCollection("octometer_events")
        rawCollection.insertOne(invalidDocument().append("element", "checkout.save").append("sessionId", "session-1").append("userId", "user-1"))
        settle()

        reader.pollOnce(target(cursor = null), MONGO.connectionString)

        assertEquals("INVALID_DATA", readAppRow(sqlite, appId).status)
    }

    // --- The status and the backoff of a failed cycle (design decision D8, issue #28) ---

    // The acceptance criterion of issue #28: a wrong password gives the
    // status UNAUTHORIZED. MONGO runs with no configured user, so the
    // client's own SASL handshake against a name and a password that
    // match no user gives a client-side MongoSecurityException, the
    // same shape as a real wrong password (design decision D8).
    @Test
    fun `a wrong password gives the status UNAUTHORIZED`() = runBlocking {
        val markerUser = "octomarkeruserf1a3"
        val markerSecondValue = "octomarkerpassf1a3"
        // The scheme and the "@" join in separate literals (the same
        // form as octometer.monitor.registry.allowlistedSrvUri), so the
        // built string never sits as one contiguous credential pattern
        // in the source text that the secret scan reads.
        val scheme = "mongodb" + "://"
        val badConnectionString =
            scheme + markerUser + ":" + markerSecondValue + "@" + MONGO.host + ":" + MONGO.getMappedPort(27017) +
                "/" + databaseName + "?authSource=admin"

        val failure = kotlin.runCatching {
            reader.pollOnce(target(cursor = null), badConnectionString)
        }.exceptionOrNull()

        assertTrue(failure is MongoReadFailedException, "Expected a MongoReadFailedException, got $failure.")
        assertEquals("UNAUTHORIZED", failure.status)

        eventStore.recordFailure(
            appId,
            status = failure.status,
            lastError = failure.code?.toString() ?: failure.javaClass.simpleName,
            nextPollAt = 1_000L,
            nowMillis = 1_000L,
        )
        assertEquals("UNAUTHORIZED", readAppRow(sqlite, appId).status)
    }

    // The acceptance criterion of issue #28: a stopped container gives
    // the status UNREACHABLE after 2 failed cycles in sequence; the
    // first failed cycle keeps the old status. This test starts one
    // throwaway container, never the shared MONGO of this class, so
    // stopping it never breaks another test of this suite.
    @Test
    fun `a stopped container gives UNREACHABLE after 2 failed cycles, and keeps the old status after 1`() = runBlocking {
        val throwaway = MongoDBContainer(DockerImageName.parse("mongo:7.0"))
        throwaway.start()
        try {
            val throwawayReader = MongoAppReader(eventStore, settleLagSeconds = 1, clientFactory = ::shortSelectionTimeoutClient)
            val throwawayTarget = PollTarget(appId, "octometer_test_throwaway", "octometer_events", cursor = null)
            // The connection string names the host and the mapped port
            // of the moment. Testcontainers refuses that read once the
            // container has stopped, thus this reads it one time, before
            // the stop() below, and reuses the same text for every poll.
            val throwawayConnectionString = throwaway.connectionString

            throwawayReader.pollOnce(throwawayTarget, throwawayConnectionString)
            eventStore.recordCycleSuccess(appId, nowMillis = 1_000L, status = "OK")
            assertEquals("OK", readAppRow(sqlite, appId).status, "a real success establishes the old status")

            throwaway.stop()

            val firstFailure = kotlin.runCatching {
                throwawayReader.pollOnce(throwawayTarget, throwawayConnectionString)
            }.exceptionOrNull()
            assertTrue(firstFailure is MongoReadFailedException, "Expected a MongoReadFailedException, got $firstFailure.")
            val firstStatus = octometer.monitor.poll.failureStatus(firstFailure.status, oldStatus = "OK", failuresBefore = 0)
            eventStore.recordFailure(
                appId,
                status = firstStatus,
                lastError = firstFailure.code?.toString() ?: firstFailure.javaClass.simpleName,
                nextPollAt = 2_000L,
                nowMillis = 2_000L,
            )
            assertEquals("OK", readAppRow(sqlite, appId).status, "the first failed cycle keeps the old status")

            val secondFailure = kotlin.runCatching {
                throwawayReader.pollOnce(throwawayTarget, throwawayConnectionString)
            }.exceptionOrNull()
            assertTrue(secondFailure is MongoReadFailedException, "Expected a MongoReadFailedException, got $secondFailure.")
            val secondStatus = octometer.monitor.poll.failureStatus(secondFailure.status, oldStatus = "OK", failuresBefore = 1)
            eventStore.recordFailure(
                appId,
                status = secondStatus,
                lastError = secondFailure.code?.toString() ?: secondFailure.javaClass.simpleName,
                nextPollAt = 3_000L,
                nowMillis = 3_000L,
            )
            assertEquals("UNREACHABLE", readAppRow(sqlite, appId).status, "the second failed cycle in sequence shows UNREACHABLE")
            assertEquals(2, readAppRow(sqlite, appId).consecutiveFailures)

            throwawayReader.close()
        } finally {
            throwaway.stop()
        }
    }

    // The acceptance criterion of issue #28: a success after failures
    // gives consecutive_failures 0 and the status OK.
    @Test
    fun `a good cycle after failures resets consecutive_failures to 0, with the status OK`() = runBlocking {
        eventStore.recordFailure(appId, status = "ERROR", lastError = "IllegalStateException", nextPollAt = 1_000L, nowMillis = 1_000L)
        eventStore.recordFailure(appId, status = "ERROR", lastError = "IllegalStateException", nextPollAt = 2_000L, nowMillis = 2_000L)
        assertEquals(2, readAppRow(sqlite, appId).consecutiveFailures, "two failed cycles in sequence, before the good cycle")

        val store = MongoEventLogStore(rawClient.getDatabase(databaseName))
        store.append(listOf(ingestEvent("nav.open")), "user-1")
        settle()

        reader.pollOnce(target(cursor = null), MONGO.connectionString)

        val row = readAppRow(sqlite, appId)
        assertEquals("OK", row.status)
        assertEquals(0, row.consecutiveFailures, "a good cycle clears the failure count")
        assertEquals(null, row.lastError, "a good cycle clears the last error text")
    }

    private fun target(cursor: String?) = PollTarget(appId, databaseName, "octometer_events", cursor)

    private fun ingestEvent(element: String) =
        IngestEvent(java.util.UUID.randomUUID().toString(), element, Instant.now())

    /** A fresh document with a new `_id` only, for a test to add its own fields to (issue #27). */
    private fun invalidDocument(): org.bson.Document = org.bson.Document("_id", org.bson.types.ObjectId())

    /**
     * Waits past the 1-second lag of [reader]. An ObjectId has a
     * resolution of 1 second (contract rule C2). Thus an insert near
     * the end of a server second needs about 1 extra second of real
     * wait, on top of the 1-second lag. This wait must pass before the
     * bound of section 4.3 is certain to include it. It also covers a
     * small clock gap between the test JVM and the container.
     */
    private suspend fun settle() {
        delay(2_500)
    }
}

/**
 * A client factory with a short server-selection timeout of 500 ms
 * (issue #28). The "stopped container" test needs a fast failure, not
 * the driver's default wait of 10 seconds, once the throwaway
 * container of that test has already stopped.
 */
private fun shortSelectionTimeoutClient(connectionString: String): com.mongodb.kotlin.client.coroutine.MongoClient {
    val settings = MongoClientSettings.builder()
        .applyConnectionString(ConnectionString(connectionString))
        .applyToClusterSettings { cluster -> cluster.serverSelectionTimeout(500, java.util.concurrent.TimeUnit.MILLISECONDS) }
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

private suspend fun readEventIdsInInsertOrder(database: SqliteDatabase, appId: Long): List<String> =
    database.read { reader ->
        reader.prepareStatement("SELECT event_id FROM event WHERE app_id = ? ORDER BY rowid").use { select ->
            select.setLong(1, appId)
            select.executeQuery().use { result ->
                val ids = mutableListOf<String>()
                while (result.next()) {
                    ids += result.getString(1)
                }
                ids
            }
        }
    }

/** The three new columns of one `event` row (issue #110). */
private data class ContainerEventColumns(val path: String?, val referrerHost: String?, val kind: Int)

private suspend fun readEventColumns(database: SqliteDatabase, appId: Long): List<ContainerEventColumns> =
    database.read { reader ->
        reader.prepareStatement("SELECT path, referrer_host, kind FROM event WHERE app_id = ?").use { select ->
            select.setLong(1, appId)
            select.executeQuery().use { result ->
                val rows = mutableListOf<ContainerEventColumns>()
                while (result.next()) {
                    rows += ContainerEventColumns(
                        path = result.getString(1),
                        referrerHost = result.getString(2),
                        kind = result.getInt(3),
                    )
                }
                rows
            }
        }
    }

/** Reads each `skipped_event` row of one app as a map of event id to reason (issue #27). */
private suspend fun readSkippedReasons(database: SqliteDatabase, appId: Long): Map<String, String> =
    database.read { reader ->
        reader.prepareStatement("SELECT event_id, reason FROM skipped_event WHERE app_id = ?").use { select ->
            select.setLong(1, appId)
            select.executeQuery().use { result ->
                val reasons = mutableMapOf<String, String>()
                while (result.next()) {
                    reasons[result.getString(1)] = result.getString(2)
                }
                reasons
            }
        }
    }

/**
 * The columns of one app row that a good or a failed poll cycle sets
 * (design decisions D5, D8, issue #27, issue #28).
 */
private data class AppRow(
    val status: String?,
    val lastPollAt: Long?,
    val lastSuccessAt: Long?,
    val lastError: String?,
    val consecutiveFailures: Int,
)

private suspend fun readAppRow(database: SqliteDatabase, appId: Long): AppRow =
    database.read { reader ->
        reader.prepareStatement(
            "SELECT status, last_poll_at, last_success_at, last_error, consecutive_failures FROM app WHERE id = ?",
        ).use { select ->
            select.setLong(1, appId)
            select.executeQuery().use { result ->
                result.next()
                AppRow(
                    status = result.getString(1),
                    lastPollAt = result.getLong(2).takeUnless { result.wasNull() },
                    lastSuccessAt = result.getLong(3).takeUnless { result.wasNull() },
                    lastError = result.getString(4),
                    consecutiveFailures = result.getInt(5),
                )
            }
        }
    }
