package octometer.monitor.mongo

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import octometer.kit.core.ingest.IngestEvent
import octometer.kit.mongo.store.MongoEventLogStore
import octometer.monitor.registerTempRoot
import octometer.monitor.store.EventStore
import octometer.monitor.store.SqliteDatabase
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests of [MongoAppReader] against a real MongoDB server
 * (issue #16, design decisions D4, D10, section 4.3). Each test needs
 * Docker; a machine with no Docker skips the whole class (the Windows CI
 * job "Monitor backend on Windows" has no Docker, but "JVM modules" on
 * Ubuntu runs it). Each event goes in through
 * [octometer.kit.mongo.store.MongoEventLogStore], the store of the app
 * side of the contract (issue #11), so the test reads the exact document
 * shape that a real app writes.
 */
@Testcontainers(disabledWithoutDocker = true)
class MongoAppReaderContainerTest {

    @Suppress("unused")
    companion object {
        @Container
        @JvmStatic
        private val MONGO = MongoDBContainer("mongo:7.0")
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
        // tests further down build their own reader with a bigger lag.
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

    @Test
    fun `an event younger than the lag is absent, and a later cycle reads it`() = runBlocking {
        val lagReader = MongoAppReader(eventStore, settleLagSeconds = 2)
        val store = MongoEventLogStore(rawClient.getDatabase(databaseName))
        store.append(listOf(ingestEvent("nav.open")), "user-1")

        val firstOutcome = lagReader.pollOnce(target(cursor = null), MONGO.connectionString)
        assertEquals(0, firstOutcome.eventsStored, "An event inserted just now must stay out of a 2-second lag.")

        delay(4_500)
        val secondOutcome = lagReader.pollOnce(target(cursor = firstOutcome.cursor), MONGO.connectionString)

        assertEquals(1, secondOutcome.eventsStored)
        assertEquals(1, countEvents(sqlite, appId))
        lagReader.close()
    }

    // Design decision D6 and section 4.3 give the bound from the server
    // time of the hello command of the primary, never a local clock. A
    // large lag of 600 seconds (10 minutes) proves this: the event, only
    // just written, stays out of the bound of "server time minus 600 s".
    // A reader that read a local clock 10 minutes ahead, minus the same
    // lag, would compute a bound close to "now", and it would wrongly
    // read the event.
    @Test
    fun `the bound follows the hello localTime of the primary, with a lag of 10 minutes`() = runBlocking {
        val tenMinuteLagReader = MongoAppReader(eventStore, settleLagSeconds = 600)
        val store = MongoEventLogStore(rawClient.getDatabase(databaseName))
        store.append(listOf(ingestEvent("nav.open")), "user-1")

        val outcome = tenMinuteLagReader.pollOnce(target(cursor = null), MONGO.connectionString)

        assertEquals(0, outcome.eventsStored, "A bound from a local clock 10 minutes ahead would wrongly include this event.")
        tenMinuteLagReader.close()
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
     * end of a server second needs a full 2 extra seconds of real wait,
     * on top of the 1-second lag, before the bound of section 4.3 is
     * certain to pass it. This wait also covers a small clock gap
     * between the test JVM and the container.
     */
    private suspend fun settle() {
        delay(3_500)
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
