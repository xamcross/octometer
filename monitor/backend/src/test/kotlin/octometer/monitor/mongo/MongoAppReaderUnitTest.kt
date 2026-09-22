package octometer.monitor.mongo

import java.io.File
import java.nio.file.Files
import java.util.Date
import kotlinx.coroutines.runBlocking
import octometer.monitor.registerTempRoot
import octometer.monitor.store.EventStore
import octometer.monitor.store.SqliteDatabase
import org.bson.Document
import org.bson.types.ObjectId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tests of issue #16 that need no Docker: the parse of one event
 * document (contract rules C1 to C6, C9), the filter of section 4.3, the
 * bound of section 4.3, and the page loop of [MongoAppReader.runCycle]
 * with a fake page source. `MongoAppReaderContainerTest` covers the real
 * MongoDB read and the client reuse of design decision D10.
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

    // --- MongoReadFailedException (the security note of the maintainer) ---

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

    private fun fakePage(pageIndex: Int, size: Int): List<Document> =
        (1..size).map { itemIndex ->
            Document("_id", ObjectId())
                .append("ts", Date(1_700_000_000_000L))
                .append("element", "e-$pageIndex-$itemIndex")
                .append("sessionId", "session-1")
                .append("userId", "user-1")
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
