package octometer.monitor.mongo

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.ReadPreference
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import octometer.monitor.store.EventStore
import octometer.monitor.store.NewEvent
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("octometer.monitor.mongo.MongoAppReader")

/** The one client pool size of D10: two connections for one app. */
private const val MAX_POOL_SIZE = 2
private const val SERVER_SELECTION_TIMEOUT_MS = 10_000L
private const val MAX_IDLE_TIME_MS = 120_000L
private const val APPLICATION_NAME = "octometer"

/** The page size and the page count of section 4.3 and of step 5. */
internal const val PAGE_LIMIT = 1000
internal const val MAX_PAGES_PER_CYCLE = 10

/** The cycle timeout of decision D6, for the one cycle of step 4 and step 5. */
private const val CYCLE_TIMEOUT_MILLIS = 45_000L

/**
 * A failed MongoDB read of one poll cycle. The message holds one fixed
 * sentence plus the class name of the real cause. It never holds a host,
 * a port, a database name, or any part of a connection string (design
 * decision D11, the security note of issue #16).
 */
class MongoReadFailedException(cause: Throwable) :
    Exception("The reader could not read MongoDB. ${cause.javaClass.simpleName}")

/** The app that one poll cycle reads (steps 1 to 5 of issue #16). */
data class PollTarget(
    val appId: Long,
    val database: String,
    val collection: String,
    val cursor: String?,
)

/** The result of one poll cycle: the stored event count, the page count, and the new cursor. */
data class PollOutcome(
    val eventsStored: Int,
    val pagesRead: Int,
    val cursor: String?,
)

/**
 * The MongoDB event reader of issue #16 (design decisions D4, D10, and
 * section 4.3). One call to [pollOnce] runs one poll cycle: it reads the
 * server time of the primary, it reads a maximum of [MAX_PAGES_PER_CYCLE]
 * pages of [PAGE_LIMIT] events each, and it commits each page with
 * [EventStore.commitPage] (D4), one page at a time.
 *
 * [clientFactory] builds one [MongoClient] from a connection string. This
 * class keeps one client for each app id, for the life of the reader
 * (design decision D10: "one client for each app, created at the first
 * poll cycle"). A cycle that runs after this one reuses the same client;
 * a new client, with a new connect cost, would run at every cycle
 * instead. [closeClient] and [close] close the kept client, or clients,
 * when the app is gone or the reader itself stops.
 *
 * The connection string parameter of [pollOnce] never reaches a field of
 * this class, a log line, or an exception message (design decision D11).
 * A failed read gives [MongoReadFailedException], with one fixed sentence
 * plus the class name of the real cause.
 */
class MongoAppReader(
    private val eventStore: EventStore,
    private val settleLagSeconds: Long,
    private val clientFactory: (String) -> MongoClient = ::defaultMongoClient,
) : AutoCloseable {

    private val clients = ConcurrentHashMap<Long, MongoClient>()

    /**
     * Runs one poll cycle for [target], with the connection string
     * [connectionString]. The whole cycle runs inside `withTimeout` of
     * [CYCLE_TIMEOUT_MILLIS] milliseconds (D6). A `CancellationException`
     * of an outer cancellation, and the `TimeoutCancellationException` of
     * this call, both still propagate; this method catches neither one.
     */
    suspend fun pollOnce(target: PollTarget, connectionString: String): PollOutcome =
        withTimeout(CYCLE_TIMEOUT_MILLIS) {
            val client = clientFor(target.appId, connectionString)
            val database = client.getDatabase(target.database)
            val bound = readBound(database)
            runCycle(target.appId, target.cursor, MAX_PAGES_PER_CYCLE, PAGE_LIMIT) { cursor ->
                readPage(database, target.collection, cursor, bound)
            }
        }

    /** Closes the kept client of one app id, for example after the app is gone. */
    fun closeClient(appId: Long) {
        clients.remove(appId)?.close()
    }

    /** Closes each kept client, for example when the monitor stops. */
    override fun close() {
        val toClose = clients.values.toList()
        clients.clear()
        toClose.forEach { it.close() }
    }

    private fun clientFor(appId: Long, connectionString: String): MongoClient =
        clients.computeIfAbsent(appId) { clientFactory(connectionString) }

    private suspend fun readBound(database: MongoDatabase): ObjectId =
        withMongoFailure {
            withContext(Dispatchers.IO) {
                val hello = database.runCommand(Document("hello", 1))
                val localTime = hello.getDate("localTime")
                    ?: error("The hello command gave no localTime field.")
                boundObjectId(localTime, settleLagSeconds)
            }
        }

    private suspend fun readPage(
        database: MongoDatabase,
        collectionName: String,
        cursor: String?,
        bound: ObjectId,
    ): List<Document> =
        withMongoFailure {
            withContext(Dispatchers.IO) {
                val collection = database.getCollection<Document>(collectionName)
                val filter = idFilter(cursor, bound)
                val page = mutableListOf<Document>()
                collection.find(filter)
                    .sort(Document("_id", 1))
                    .limit(PAGE_LIMIT)
                    .batchSize(PAGE_LIMIT)
                    .collect { page += it }
                page
            }
        }

    /**
     * Runs the page loop of step 5 (D4). [fetchPage] hides the real
     * MongoDB call in [pollOnce], or a fake page list in a test with no
     * Docker. The loop stops at [maxPages], or at the first page smaller
     * than [pageLimit] (the last page of the cycle).
     */
    internal suspend fun runCycle(
        appId: Long,
        initialCursor: String?,
        maxPages: Int,
        pageLimit: Int,
        fetchPage: suspend (cursor: String?) -> List<Document>,
    ): PollOutcome {
        var cursor = initialCursor
        var pagesRead = 0
        var eventsStored = 0
        while (pagesRead < maxPages) {
            val page = fetchPage(cursor)
            if (page.isEmpty()) break
            val events = page.map(::parseEvent)
            val newCursor = page.last().getObjectId("_id").toHexString()
            eventStore.commitPage(appId, events, newCursor)
            cursor = newCursor
            eventsStored += events.size
            pagesRead += 1
            if (page.size < pageLimit) break
        }
        return PollOutcome(eventsStored, pagesRead, cursor)
    }

    /**
     * Wraps a MongoDB call: a `CancellationException` of an active
     * coroutine still propagates (lesson 2 of the brief); every other
     * exception becomes [MongoReadFailedException], and one warn-level
     * log line names the exception class, never the connection string
     * and never the server text of the real cause.
     */
    private suspend fun <T> withMongoFailure(block: suspend () -> T): T =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            log.warn("The MongoDB read failed. {}", failure.javaClass.simpleName)
            throw MongoReadFailedException(failure)
        }
}

/**
 * Parses one event document into [NewEvent] (contract rules C1 to C6). An
 * unknown field of the document stays out of the result (contract rule
 * C9); this function reads only the five named fields. A missing field,
 * or a wrong BSON type, is the concern of issue #27 (design decision D5);
 * this function still throws for that case, and the caller of [runCycle]
 * does not catch it.
 */
internal fun parseEvent(document: Document): NewEvent {
    val id = document.getObjectId("_id")
    val ts = document.getDate("ts")
    val element = document.getString("element")
    val sessionId = document.getString("sessionId")
    val userId = document.getString("userId")
    return NewEvent(
        eventId = id.toHexString(),
        ts = ts.time,
        element = element,
        sessionId = sessionId,
        userId = userId,
    )
}

/**
 * The bound of section 4.3: `ObjectId.getSmallestWithDate(serverTime -
 * lag)`. [serverTime] is the `localTime` field of the `hello` command of
 * the primary, never a local clock of the monitor process (step 2 and
 * step 3 of issue #16).
 */
internal fun boundObjectId(serverTime: Date, settleLagSeconds: Long): ObjectId =
    ObjectId.getSmallestWithDate(Date(serverTime.time - settleLagSeconds * 1000))

/**
 * The filter of section 4.3: `{_id: {$gt: cursor, $lt: bound}}`, sort
 * `{_id: 1}`. Without a cursor, the filter holds no `$gt` term (the note
 * of the review of pull request #83): `{_id: {$gt: null}}` matches no
 * document in MongoDB, thus the first cycle of a new app would read
 * nothing.
 */
internal fun idFilter(cursor: String?, bound: ObjectId): Bson {
    val range = Document("\$lt", bound)
    if (cursor != null) {
        range["\$gt"] = ObjectId(cursor)
    }
    return Document("_id", range)
}

private fun defaultMongoClient(connectionString: String): MongoClient {
    val settings = MongoClientSettings.builder()
        .applyConnectionString(ConnectionString(connectionString))
        .readPreference(ReadPreference.primary())
        .applicationName(APPLICATION_NAME)
        .applyToConnectionPoolSettings { pool ->
            pool.maxSize(MAX_POOL_SIZE)
            pool.maxConnectionIdleTime(MAX_IDLE_TIME_MS, TimeUnit.MILLISECONDS)
        }
        .applyToClusterSettings { cluster ->
            cluster.serverSelectionTimeout(SERVER_SELECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
        .build()
    return MongoClient.create(settings)
}
