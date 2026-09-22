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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import octometer.monitor.registry.ConnectionStringCheck
import octometer.monitor.registry.ConnectionStringValidator
import octometer.monitor.store.EventStore
import octometer.monitor.store.NewEvent
import org.bson.BsonBinaryWriter
import org.bson.Document
import org.bson.codecs.DocumentCodec
import org.bson.codecs.EncoderContext
import org.bson.conversions.Bson
import org.bson.io.BasicOutputBuffer
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("octometer.monitor.mongo.MongoAppReader")

/** The client pool size of design decision D10: two connections for one app. */
private const val MAX_POOL_SIZE = 2
private const val SERVER_SELECTION_TIMEOUT_MS = 10_000L
private const val MAX_IDLE_TIME_MS = 120_000L
private const val APPLICATION_NAME = "octometer"

/** The page size and the page count of section 4.3 and of step 5. */
internal const val PAGE_LIMIT = 1000
internal const val MAX_PAGES_PER_CYCLE = 10

/**
 * The byte budget of one page (MINOR finding of each review of pull
 * request #160). A page stops early when the next document would push
 * it over this budget. The page always keeps its first document, even
 * when that one document alone is over the budget.
 */
internal const val PAGE_BYTE_BUDGET = 8L * 1024 * 1024

/** The cycle timeout of design decision D6, for the page loop of step 5. */
internal const val CYCLE_TIMEOUT_MILLIS = 45_000L

/**
 * A failed MongoDB read of one poll cycle. The message holds one fixed
 * sentence plus the class name of the real cause.
 *
 * The message never holds a host, a port, a database name, or a part of
 * a connection string (design decision D11, the security note of issue
 * #16). This class keeps no `cause`, so a stack trace of this exception
 * cannot reach the driver message either.
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
 * section 4.3).
 *
 * One call to [pollOnce] runs one poll cycle. It reads the server time
 * of the primary, then it reads a maximum of [MAX_PAGES_PER_CYCLE]
 * pages of [PAGE_LIMIT] events each. It commits each page with
 * [EventStore.commitPage] (D4), one page at a time.
 *
 * [clientFactory] builds one [MongoClient] from a connection string.
 * This class keeps one client for each app id (design decision D10). A
 * later cycle reuses the same client, so it pays no new connect cost.
 * [closeClient] and [close] close a kept client, when the app is gone
 * or the reader itself stops.
 *
 * [serverTimeSource] reads the server time of the primary. The default,
 * [helloLocalTime], reads the `localTime` field of the `hello` command
 * (section 4.3). A test can inject a different source, to prove that
 * the bound of [readBound] follows this value, and never a local clock
 * of the monitor process.
 *
 * The connection string parameter of [pollOnce] never reaches a field
 * of this class, a log line, or an exception message (design decision
 * D11). The build of the client, through [clientFor], runs inside
 * [withMongoFailure]. A failed parse of the connection string, or a
 * failed client build, therefore also gives [MongoReadFailedException],
 * with no part of the connection string in its message (BLOCKER 1 of
 * the security review of pull request #160).
 */
class MongoAppReader(
    private val eventStore: EventStore,
    private val settleLagSeconds: Long,
    private val clientFactory: (String) -> MongoClient = ::defaultMongoClient,
    private val serverTimeSource: suspend (MongoDatabase) -> Date = ::helloLocalTime,
    internal val cycleTimeoutMillis: Long = CYCLE_TIMEOUT_MILLIS,
) : AutoCloseable {

    private val clients = ConcurrentHashMap<Long, MongoClient>()

    /**
     * Runs one poll cycle for [target], with the connection string
     * [connectionString]. The page loop runs inside `withTimeout` of
     * [cycleTimeoutMillis] milliseconds (design decision D6).
     *
     * A real cancellation of the calling coroutine still propagates.
     * This method, through [withMongoFailure], catches a
     * `CancellationException` only to check whether it is real (lesson
     * 2 of the brief); it then throws the real one again unchanged.
     */
    suspend fun pollOnce(target: PollTarget, connectionString: String): PollOutcome {
        val database = openDatabase(target.appId, connectionString, target.database)
        val bound = readBound(database)
        return runCycleWithTimeout(target.appId, target.cursor, MAX_PAGES_PER_CYCLE, PAGE_LIMIT) { cursor ->
            readPage(database, target.collection, cursor, bound, PAGE_LIMIT)
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

    /**
     * Opens the database of one app, with the connection string
     * [connectionString]. The call to [clientFor] sits inside
     * [withMongoFailure], so a failed parse of the connection string,
     * or a failed client build, gives [MongoReadFailedException] too.
     */
    private suspend fun openDatabase(appId: Long, connectionString: String, databaseName: String): MongoDatabase =
        withMongoFailure {
            withContext(Dispatchers.IO) {
                clientFor(appId, connectionString).getDatabase(databaseName)
            }
        }

    private fun clientFor(appId: Long, connectionString: String): MongoClient =
        clients.computeIfAbsent(appId) { clientFactory(connectionString) }

    private suspend fun readBound(database: MongoDatabase): ObjectId =
        withMongoFailure {
            withContext(Dispatchers.IO) {
                boundObjectId(serverTimeSource(database), settleLagSeconds)
            }
        }

    private suspend fun readPage(
        database: MongoDatabase,
        collectionName: String,
        cursor: String?,
        bound: ObjectId,
        pageLimit: Int,
    ): List<Document> =
        withMongoFailure {
            withContext(Dispatchers.IO) {
                val collection = database.getCollection<Document>(collectionName)
                val filter = idFilter(cursor, bound)
                val page = mutableListOf<Document>()
                var pageBytes = 0L
                collection.find(filter)
                    .sort(Document("_id", 1))
                    .limit(pageLimit)
                    .batchSize(pageLimit)
                    .takeWhile { document ->
                        val documentBytes = bsonByteSize(document)
                        val fitsBudget = page.isEmpty() || pageBytes + documentBytes <= PAGE_BYTE_BUDGET
                        if (fitsBudget) {
                            page += document
                            pageBytes += documentBytes
                        }
                        fitsBudget
                    }
                    .collect {}
                page
            }
        }

    /**
     * Wraps the page loop of step 5 (D4) inside `withTimeout` of
     * [cycleTimeoutMillis] milliseconds (design decision D6). A test
     * builds a reader with a small [cycleTimeoutMillis], to prove that
     * a slow page source trips the timeout, with no long real wait.
     */
    internal suspend fun runCycleWithTimeout(
        appId: Long,
        initialCursor: String?,
        maxPages: Int,
        pageLimit: Int,
        fetchPage: suspend (cursor: String?) -> List<Document>,
    ): PollOutcome =
        withTimeout(cycleTimeoutMillis) {
            runCycle(appId, initialCursor, maxPages, pageLimit, fetchPage)
        }

    /**
     * Runs the page loop of step 5 (D4). [fetchPage] hides the real
     * MongoDB call in [pollOnce], or a fake page list in a test with no
     * Docker. The loop stops at [maxPages], or at the first page
     * smaller than [pageLimit] (the last page of the cycle).
     *
     * A document that fails the parse of [parseEvent] never stops the
     * page. [runCycle] skips it. The loop still moves the cursor past
     * it, in the same page commit. One WARN line names the skipped
     * count of the page, with no field of a document.
     *
     * Issue #27 adds the `skipped_event` row and the status
     * `INVALID_DATA` for this case (design decision D5). This round
     * only stops the old failure of "one bad document stops the app
     * for ever" (Kotlin review, MAJOR 2 of pull request #160).
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
            val events = mutableListOf<NewEvent>()
            var skippedCount = 0
            for (document in page) {
                val event = parseEventOrNull(document)
                if (event != null) events += event else skippedCount += 1
            }
            if (skippedCount > 0) {
                log.warn("The reader skipped {} invalid document(s) of one page.", skippedCount)
            }
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
     * Wraps a MongoDB call.
     *
     * A real cancellation of the calling coroutine still propagates.
     * This branch checks [currentCoroutineContext] first, and it
     * throws the same `CancellationException` again only when the
     * coroutine is no longer active (lesson 2 of the brief). Each
     * other `CancellationException`, from inside the driver, becomes
     * [MongoReadFailedException], the same as each other exception.
     *
     * One warn-level log line names the exception class only. The line
     * never holds the connection string, and never the server text of
     * the real cause.
     */
    private suspend fun <T> withMongoFailure(block: suspend () -> T): T =
        try {
            block()
        } catch (cancellation: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancellation
            log.warn("The MongoDB read failed. {}", cancellation.javaClass.simpleName)
            throw MongoReadFailedException(cancellation)
        } catch (failure: Exception) {
            log.warn("The MongoDB read failed. {}", failure.javaClass.simpleName)
            throw MongoReadFailedException(failure)
        }
}

/**
 * Parses one event document into [NewEvent] (contract rules C1 to C6).
 * An unknown field of the document stays out of the result (contract
 * rule C9); this function reads only the five named fields.
 *
 * A missing field, or a wrong BSON type, throws. [parseEventOrNull]
 * turns that throw into `null`, for the skip of [MongoAppReader.runCycle].
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
 * Parses one document, or returns `null` for an invalid one (design
 * decision D5, Kotlin review MAJOR 2 of pull request #160). The caller
 * counts the `null` result, and it logs the count only, never a field
 * of the document.
 */
private fun parseEventOrNull(document: Document): NewEvent? =
    try {
        parseEvent(document)
    } catch (invalid: Exception) {
        null
    }

/**
 * The bound of section 4.3: `ObjectId.getSmallestWithDate(serverTime -
 * lag)`. [serverTime] is the value of [MongoAppReader]'s
 * `serverTimeSource`, the `localTime` field of `hello` by default. It
 * is never a local clock of the monitor process (step 2 and step 3 of
 * issue #16).
 */
internal fun boundObjectId(serverTime: Date, settleLagSeconds: Long): ObjectId =
    ObjectId.getSmallestWithDate(Date(serverTime.time - settleLagSeconds * 1000))

/**
 * The filter of section 4.3: `{_id: {$gt: cursor, $lt: bound}}`, sort
 * `{_id: 1}`.
 *
 * Without a cursor, the filter holds no `$gt` term. The note of the
 * review of pull request #83 explains why: `{_id: {$gt: null}}` matches
 * no document in MongoDB, so the first cycle of a new app would read
 * nothing.
 */
internal fun idFilter(cursor: String?, bound: ObjectId): Bson {
    val range = Document("\$lt", bound)
    if (cursor != null) {
        range["\$gt"] = ObjectId(cursor)
    }
    return Document("_id", range)
}

/**
 * Reads the `localTime` field of the `hello` command of the primary
 * (section 4.3). This is the default of [MongoAppReader]'s
 * `serverTimeSource`.
 */
internal suspend fun helloLocalTime(database: MongoDatabase): Date =
    database.runCommand(Document("hello", 1)).getDate("localTime")
        ?: error("The hello command gave no localTime field.")

/**
 * The BSON-encoded byte size of [document] (the page byte budget of
 * [PAGE_BYTE_BUDGET]).
 */
private fun bsonByteSize(document: Document): Long {
    val buffer = BasicOutputBuffer()
    val writer = BsonBinaryWriter(buffer)
    try {
        DocumentCodec().encode(writer, document, EncoderContext.builder().build())
        return buffer.size.toLong()
    } finally {
        writer.close()
        buffer.close()
    }
}

/**
 * Builds the default [MongoClient] of design decision D10.
 *
 * [ConnectionStringValidator.check] runs first (D10: "the monitor
 * checks the scheme with a string test before the driver parses the
 * URI"). The registry already checks a new connection string at the
 * save (D11); this second check catches a value that a person edited
 * by hand in the secrets file.
 *
 * The caller of this function, [MongoAppReader.clientFor], runs inside
 * `withMongoFailure`. A failed check, and a failed [ConnectionString]
 * parse, both give [MongoReadFailedException], with no part of the
 * connection string in the message (BLOCKER 1 of the security review
 * of pull request #160).
 */
private fun defaultMongoClient(connectionString: String): MongoClient {
    val check = ConnectionStringValidator.check(connectionString)
    if (check is ConnectionStringCheck.Invalid) {
        throw IllegalArgumentException(check.message)
    }
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
