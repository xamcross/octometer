package octometer.monitor.mongo

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.ReadPreference
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import java.security.MessageDigest
import java.time.Clock
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
import octometer.monitor.store.SkippedEvent
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

/**
 * The socket read timeout of issue #17, decision 3 (MAJOR 1 of the
 * security review). With no read timeout, a host can accept the TCP
 * connection and then stall a command, for example `hello`. That stall
 * can hold a socket read for ever. The cycle timeout of
 * [CYCLE_TIMEOUT_MILLIS] now wraps the whole cycle too, so the two
 * bounds work together.
 */
private const val SOCKET_READ_TIMEOUT_MS = 30_000L

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

/** The status of D8 for a poll cycle with no skipped document. */
internal const val STATUS_OK = "OK"

/** The status of D8 for a cycle that skipped one document or more (design decision D5). */
internal const val STATUS_INVALID_DATA = "INVALID_DATA"

/**
 * A failed MongoDB read of one poll cycle. The message holds one fixed
 * sentence plus the class name of the real cause.
 *
 * The message never holds a host, a port, or a database name. It never
 * holds a part of a connection string either (design decision D11, the
 * security note of issue #16). This class keeps no `cause`, so a stack
 * trace of this exception cannot reach the driver message either.
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

/** The result of one poll cycle: the stored count, the page count, the cursor, and the skipped count (issue #27). */
data class PollOutcome(
    val eventsStored: Int,
    val pagesRead: Int,
    val cursor: String?,
    val eventsSkipped: Int = 0,
)

/**
 * The kept client of one app id, with the SHA-256 hex of the connection
 * string that built it (design decision D10, issue #21). This class never
 * holds the connection string itself: design decision D11 forbids a
 * connection string in a field of the reader (BLOCKER 1, security review
 * of issue #16). [MongoAppReader.clientFor] compares [connectionStringHash]
 * on each cycle, so a changed value closes the old client and builds a
 * fresh one, and a PATCH of the connection string takes effect at the
 * next poll cycle.
 */
private data class CachedClient(val connectionStringHash: String, val client: MongoClient)

/**
 * The SHA-256 hex text of [text] (the form of [octometer.monitor.mongo]'s
 * client cache key, the same rule as the long-key hash of the ingest rate
 * limiter, issue #33). Every JDK 21 runtime provides SHA-256 (the Java
 * Cryptography Architecture standard algorithm list), so this never
 * throws in practice.
 */
private fun sha256Hex(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(text.toByteArray(Charsets.UTF_8))
    val hex = StringBuilder(hash.size * 2)
    for (byte in hash) {
        hex.append(Character.forDigit((byte.toInt() shr 4) and 0xF, 16))
        hex.append(Character.forDigit(byte.toInt() and 0xF, 16))
    }
    return hex.toString()
}

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
 * (section 4.3). A test can inject a different source. It then proves
 * that the bound of [readBound] follows this value, and never a local
 * clock of the monitor process.
 *
 * The connection string parameter of [pollOnce] never reaches a field
 * of this class, a log line, or an exception message (design decision
 * D11). The build of the client, through [clientFor], runs inside
 * [withMongoFailure]. A failed parse of the connection string, or a
 * failed client build, therefore also gives [MongoReadFailedException].
 * Its message holds no part of the connection string (BLOCKER 1 of the
 * security review of pull request #160).
 *
 * [clock] gives the time of [pollOnce]'s status update (issue #27, D8).
 * The default, `Clock.systemUTC()`, matches the epoch-millisecond UTC
 * unit of `app.last_poll_at`, `app.last_success_at`, and `event.ts`. A
 * test injects a fixed clock, for a stable assertion.
 */
class MongoAppReader(
    private val eventStore: EventStore,
    private val settleLagSeconds: Long,
    private val clientFactory: (String) -> MongoClient = ::defaultMongoClient,
    private val serverTimeSource: suspend (MongoDatabase) -> Date = ::helloLocalTime,
    internal val cycleTimeoutMillis: Long = CYCLE_TIMEOUT_MILLIS,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {

    private val clients = ConcurrentHashMap<Long, CachedClient>()

    /**
     * Runs one poll cycle for [target], with the connection string
     * [connectionString]. The whole cycle now runs inside one
     * `withTimeout` of [cycleTimeoutMillis] milliseconds (issue #17,
     * decision 3). That timeout covers the client open, the
     * server-time read, and the page loop. An earlier form left the
     * client open and the server-time read outside that timeout. A
     * stalled `hello` command could then hold the poll for ever. A good
     * cycle then sets the status, `last_poll_at`, and `last_success_at`
     * of the app row (D5, D8, issue #27).
     *
     * A real cancellation of the calling coroutine still propagates.
     * This method, through [withMongoFailure], catches a
     * `CancellationException` only to check whether it is real (lesson
     * 2 of the brief). It then throws the real one again unchanged.
     */
    suspend fun pollOnce(target: PollTarget, connectionString: String): PollOutcome =
        withTimeout(cycleTimeoutMillis) {
            val database = openDatabase(target.appId, connectionString, target.database)
            val bound = readBound(database)
            val outcome = runCycle(target.appId, target.cursor, MAX_PAGES_PER_CYCLE, PAGE_LIMIT) { cursor ->
                readPage(database, target.collection, cursor, bound, PAGE_LIMIT)
            }
            recordCycleSuccess(target.appId, outcome.eventsSkipped)
            outcome
        }

    /**
     * Records the status of a good cycle (issue #27, D5, D8). It runs
     * only after [runCycle] returns with no throw. A failed cycle then
     * changes none of the three columns of the app row this round
     * (issue #28 adds the failure status).
     */
    private suspend fun recordCycleSuccess(appId: Long, eventsSkipped: Int) {
        val status = if (eventsSkipped > 0) STATUS_INVALID_DATA else STATUS_OK
        eventStore.recordCycleSuccess(appId, clock.millis(), status)
    }

    /** Closes the kept client of one app id, for example after the app is gone. */
    fun closeClient(appId: Long) {
        clients.remove(appId)?.client?.close()
    }

    /** Closes each kept client, for example when the monitor stops. */
    override fun close() {
        // MINOR 4 of the third Kotlin review (issue #17): clients is a
        // ConcurrentHashMap. Iterable.toList() can throw
        // NoSuchElementException for a size of one. A concurrent remove
        // can land between its size read and its iterator read (the
        // same defect as PollScheduler.stop, MAJOR 1). ArrayList's
        // constructor takes one safe copy instead.
        val toClose = ArrayList(clients.values)
        clients.clear()
        toClose.forEach { it.client.close() }
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

    /**
     * Gives the kept client of [appId], or builds a fresh one (design
     * decision D10, issue #21). A kept client of the same connection
     * string stays; each cycle then pays no new connect cost. A kept
     * client of a different connection string closes at once, and a
     * fresh client of the new string takes its place, so the next cycle
     * after a PATCH of the connection string reads the new source.
     *
     * This method compares [connectionString] by its SHA-256 hex only
     * ([sha256Hex]), never by the string itself (design decision D11,
     * BLOCKER 1 of the security review of issue #16). [connectionString]
     * sits in one local variable, and in the closure of the one
     * [ConcurrentHashMap.compute] call below; the JVM garbage collector
     * reclaims that closure once this method returns, so no string of
     * this call survives inside a field of [MongoAppReader] or of
     * [CachedClient] afterward.
     *
     * [PollScheduler] never starts two polls of the same app id at the
     * same time (its own `activePolls` guard), so this method never
     * runs twice for one [appId] at once; the plain read-and-replace of
     * [ConcurrentHashMap.compute] needs no further lock.
     */
    internal fun clientFor(appId: Long, connectionString: String): MongoClient {
        val hash = sha256Hex(connectionString)
        return clients.compute(appId) { _, cached ->
            if (cached != null && cached.connectionStringHash == hash) {
                cached
            } else {
                cached?.client?.close()
                CachedClient(hash, clientFactory(connectionString))
            }
        }!!.client
    }

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
     * [cycleTimeoutMillis] milliseconds. [pollOnce] no longer calls this
     * function (issue #17, decision 3). Its own `withTimeout` now covers
     * the client open and the server-time read too, not the page loop
     * alone. This function stays, for a direct test of the page-loop
     * timeout, with no client and no Docker. A test builds a reader
     * with a small [cycleTimeoutMillis]. It then proves that a slow
     * page source trips the timeout, with no long real wait.
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
     * [invalidReason] checks each document. An invalid one never stops
     * the page. [runCycle] skips it and writes its `skipped_event` row,
     * with the fixed reason (design decision D5, issue #27). The loop
     * still moves the cursor past it, in the same page commit. One WARN
     * line names the skipped count of the page, with no field of a
     * document.
     *
     * [pollOnce] sets the status `INVALID_DATA` after a cycle with one
     * skip or more, else `OK` (D8). This fixes the old failure of "one
     * bad document stops the app for ever" (Kotlin review, MAJOR 2 of
     * pull request #160).
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
        var eventsSkipped = 0
        while (pagesRead < maxPages) {
            val page = fetchPage(cursor)
            if (page.isEmpty()) break
            val events = mutableListOf<NewEvent>()
            val skipped = mutableListOf<SkippedEvent>()
            for (document in page) {
                val reason = invalidReason(document)
                if (reason == null) {
                    events += parseEvent(document)
                } else {
                    skipped += SkippedEvent(document.getObjectId("_id").toHexString(), reason)
                }
            }
            if (skipped.isNotEmpty()) {
                log.warn("The reader skipped {} invalid document(s) of one page.", skipped.size)
            }
            val newCursor = page.last().getObjectId("_id").toHexString()
            eventStore.commitPage(appId, events, newCursor, skippedEvents = skipped)
            cursor = newCursor
            eventsStored += events.size
            eventsSkipped += skipped.size
            pagesRead += 1
            if (page.size < pageLimit) break
        }
        return PollOutcome(eventsStored, pagesRead, cursor, eventsSkipped)
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
     * One debug-level log line names the exception class only (issue
     * #17, decision 4). The scheduler owns the one WARN line of a
     * failed cycle; this line would double it otherwise. The line here
     * never holds the connection string, and never the server text of
     * the real cause.
     */
    private suspend fun <T> withMongoFailure(block: suspend () -> T): T =
        try {
            block()
        } catch (cancellation: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancellation
            log.debug("The MongoDB read failed. {}", cancellation.javaClass.simpleName)
            throw MongoReadFailedException(cancellation)
        } catch (failure: Exception) {
            log.debug("The MongoDB read failed. {}", failure.javaClass.simpleName)
            throw MongoReadFailedException(failure)
        }
}

/**
 * Parses one event document into [NewEvent] (contract rules C1 to C6).
 * An unknown field of the document stays out of the result (contract
 * rule C9). This function reads only the five named fields.
 *
 * [MongoAppReader.runCycle] calls [invalidReason] first, so this
 * function runs only for a document that already passed that check.
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
 * Checks one event document against section 4.1. It returns a fixed
 * reason, or `null` for a valid document (design decision D5, decision
 * 1 of issue #27). A reason names the field and the rule only. It never
 * holds a value of the document.
 *
 * An absent `userId` field is a valid anonymous event, the same as an
 * explicit `null` value (contract rule C6). An empty `userId` string is
 * invalid.
 */
internal fun invalidReason(document: Document): String? {
    val ts = document["ts"]
    if (ts == null) return "ts missing"
    if (ts !is Date) return "ts wrong type"
    val element = document["element"]
    if (element == null) return "element missing"
    if (element !is String) return "element wrong type"
    val sessionId = document["sessionId"]
    if (sessionId == null) return "sessionId missing"
    if (sessionId !is String) return "sessionId wrong type"
    val userId = document["userId"]
    if (userId != null && userId !is String) return "userId wrong type"
    if (userId is String && userId.isEmpty()) return "userId empty"
    return null
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
 * review of pull request #83 explains why. The filter `{_id: {$gt:
 * null}}` matches no document in MongoDB. Thus the first cycle of a new
 * app would read nothing.
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
 * save (D11). This second check catches a value that a person edited
 * by hand in the secrets file.
 *
 * The caller of this function, [MongoAppReader.clientFor], runs inside
 * `withMongoFailure`. A failed check, and a failed [ConnectionString]
 * parse, both give [MongoReadFailedException]. Its message holds no
 * part of the connection string (BLOCKER 1 of the security review of
 * pull request #160).
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
        .applyToSocketSettings { socket ->
            socket.readTimeout(SOCKET_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
        .build()
    return MongoClient.create(settings)
}
