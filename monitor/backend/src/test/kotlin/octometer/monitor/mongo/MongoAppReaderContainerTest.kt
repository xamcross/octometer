package octometer.monitor.mongo

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import java.io.File
import java.nio.file.Files
import java.time.Instant
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
 * (issue #16, design decisions D4, D10, section 4.3, plus correction
 * round 1 of pull request #160: BLOCKER 1 and BLOCKER 2 of the Kotlin
 * review). Each test needs Docker; a machine with no Docker skips the
 * whole class. The job "JVM modules" on Ubuntu runs it; the job
 * "Monitor backend on Windows" has no Docker and skips it by design
 * (`monitor/backend/build.gradle.kts` guards the Ubuntu job only).
 *
 * Each event goes in through
 * [octometer.kit.mongo.store.MongoEventLogStore], the store of the app
 * side of the contract (issue #11), so the test reads the exact
 * document shape that a real app writes.
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
        // A lag of 1 second here: most tests of this class check the page
        // loop and the document shape, not the lag itself, so each of
        // them calls settle() below before the first poll. The two lag
        // tests further down build their own reader with its own lag.
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

        // A second full drain must add 0 new rows: the delivery is "at
        // least once" at the MongoDB level, but the SQLite primary key
        // (app_id, event_id) drops a duplicate (section 4.3).
        val replay = reader.pollOnce(target(cursor = readCursor(sqlite, appId)), MONGO.connectionString)
        assertEquals(0, replay.eventsStored)
        assertEquals(2500, countEvents(sqlite, appId))
    }

    // BLOCKER 2, Kotlin review of pull request #160. ObjectId's
    // getSmallestWithDate truncates the server time to a whole second.
    // A test that inserts and polls at once always gets 0 events from
    // the truncation alone, with no help from the lag, so it cannot
    // fail for a broken lag. This test waits past the second of the
    // insert first, so only the lag can explain the first 0 result.
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
    // hello proves the seam is live: with the real hello time and a
    // lag of 1 second, a just-written event would stay out; with the
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
    // not only ERROR, and it asserts that no line holds either marker.
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

        // Simulates the TTL delete of section 4.3: the document that the
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

    private fun target(cursor: String?) = PollTarget(appId, databaseName, "octometer_events", cursor)

    private fun ingestEvent(element: String) =
        IngestEvent(java.util.UUID.randomUUID().toString(), element, Instant.now())

    /**
     * Waits past the 1-second lag of [reader]. An ObjectId has a
     * resolution of 1 second (contract rule C2), so an insert near the
     * end of a server second needs about 1 extra second of real wait,
     * on top of the 1-second lag, before the bound of section 4.3 is
     * certain to pass it. This wait also covers a small clock gap
     * between the test JVM and the container.
     */
    private suspend fun settle() {
        delay(2_500)
    }
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
