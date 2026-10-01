package octometer.monitor.collect

import com.mongodb.MongoCommandException
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoCollection
import java.time.Clock
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import octometer.kit.core.ingest.IngestEvent
import org.bson.Document
import org.slf4j.LoggerFactory

/** The collection name of contract rule C1, the same name as [octometer.kit.mongo.store.MongoEventLogStore]. */
private const val COLLECTION_NAME = "octometer_events"

/** The TTL index name, the same name as [octometer.kit.mongo.store.MongoEventLogStore]. */
private const val TTL_INDEX_NAME = "ts_ttl"

/** The TTL of design decision D57: 365 days, fixed for the hosted model. */
private const val TTL_SECONDS = 365L * 24 * 60 * 60

/** The index options conflict error code of the MongoDB server (the same code as `MongoEventLogStore`). */
private const val INDEX_OPTIONS_CONFLICT_ERROR_CODE = 85

/** The event cap of design decision D21 (50 000 per app for the hosted model, D57). */
private const val EVENT_CAP_PER_APP = 50_000L

/** The refresh interval of the event cap estimate, the same interval as `EventCapGuard`. */
private const val EVENT_CAP_REFRESH_MILLIS = 60_000L

private val log = LoggerFactory.getLogger("octometer.monitor.collect.CollectorStore")

/**
 * The write of design decision D50: the collector writes the event
 * document itself, with the shared cluster client of issue #296, into
 * `app_<id>`, the collection `octometer_events` only (the maintainer's
 * note of 2026-10-01: no `kit/jvm-mongo` store). The document shape
 * mirrors `octometer.kit.mongo.store.MongoEventLogStore.toDocument`
 * exactly, so the reader (`MongoAppReader`) needs no change.
 *
 * This class prepares one database at most one time: the TTL index
 * creation of [prepareApp] runs off the request path of every later
 * request for that app (the brief review of 2026-10-01, B4). The event
 * cap of design decision D21 is this class's own Kotlin equivalent of
 * `octometer.kit.core.store.EventCapGuard`: the kit's own class takes a
 * non-suspending [octometer.kit.core.store.EventCountEstimator], and the
 * coroutine MongoDB driver's `estimatedDocumentCount()` is a suspend
 * function, so this class reimplements the same cache-and-refresh rule
 * natively, rather than bridge a suspend call across that Java
 * interface.
 */
class CollectorStore(private val clientFactory: () -> MongoClient, private val clock: Clock = Clock.systemUTC()) {

    private val prepared = ConcurrentHashMap<Long, PreparedApp>()
    private val prepareMutex = Mutex()

    /**
     * Writes [events] into the app's database, each with [userId]
     * (issue #300, design decision D54; `null` for an anonymous click,
     * unchanged from issue #297). Returns `false`, with no write, when
     * the event cap of design decision D21 already holds
     * [EVENT_CAP_PER_APP] events or more for this app; the caller then
     * answers 204, the same answer as a stored batch (contract rule
     * C19). [events] must not be empty.
     */
    suspend fun write(appId: Long, databaseName: String, events: List<IngestEvent>, userId: String? = null): Boolean {
        require(events.isNotEmpty()) { "events must hold one event or more" }
        val app = preparedApp(appId, databaseName)
        if (app.eventCap.isOverCap()) {
            return false
        }
        val documents = events.map { event -> toDocument(event, userId) }
        app.collection.insertMany(documents)
        app.eventCap.countAppended(documents.size)
        return true
    }

    private suspend fun preparedApp(appId: Long, databaseName: String): PreparedApp {
        prepared[appId]?.let { if (it.databaseName == databaseName) return it }
        return prepareMutex.withLock {
            val existing = prepared[appId]
            if (existing != null && existing.databaseName == databaseName) {
                existing
            } else {
                val collection = clientFactory().getDatabase(databaseName).getCollection<Document>(COLLECTION_NAME)
                ensureTtlIndex(collection)
                val entry = PreparedApp(databaseName, collection, AppEventCap(collection, clock))
                prepared[appId] = entry
                entry
            }
        }
    }

    private suspend fun ensureTtlIndex(collection: MongoCollection<Document>) {
        try {
            collection.createIndex(
                Indexes.ascending("ts"),
                IndexOptions().name(TTL_INDEX_NAME).expireAfter(TTL_SECONDS, TimeUnit.SECONDS),
            )
        } catch (conflict: MongoCommandException) {
            if (conflict.errorCode == INDEX_OPTIONS_CONFLICT_ERROR_CODE) {
                runCollMod(collection)
            } else {
                warnIndexError("createIndex", conflict)
            }
        } catch (failure: RuntimeException) {
            warnIndexError("createIndex", failure)
        }
    }

    private suspend fun runCollMod(collection: MongoCollection<Document>) {
        try {
            val command = Document("collMod", COLLECTION_NAME)
                .append(
                    "index",
                    Document("keyPattern", Document("ts", 1)).append("expireAfterSeconds", TTL_SECONDS),
                )
            clientFactory().getDatabase(collection.namespace.databaseName).runCommand(command)
        } catch (failure: RuntimeException) {
            warnIndexError("collMod", failure)
        }
    }

    private fun warnIndexError(step: String, cause: RuntimeException) {
        val errorCode = (cause as? MongoCommandException)?.errorCode?.toString() ?: "unknown"
        log.warn("The collector's TTL index step '{}' failed with the MongoDB error code {}. The write continues.", step, errorCode)
    }

    private fun toDocument(event: IngestEvent, userId: String?): Document {
        val document = Document()
            .append("ts", Date.from(event.ts()))
            .append("element", event.element())
            .append("sessionId", event.sessionId())
            // Issue #300 (design decision D54): the collector stores the
            // body's own user id, dropped to null already by
            // IngestPipeline.processBatch for an invalid value (the
            // brief review of 2026-10-01, point 25).
            .append("userId", userId)
        if (event.path() != null) {
            document.append("path", event.path())
        }
        if (event.referrerHost() != null) {
            document.append("referrerHost", event.referrerHost())
        }
        return document
    }

    private data class PreparedApp(
        val databaseName: String,
        val collection: MongoCollection<Document>,
        val eventCap: AppEventCap,
    )
}

/**
 * The per-app event cap cache of design decision D21 (50 000 events, D57),
 * the native Kotlin equivalent of `octometer.kit.core.store.EventCapGuard`
 * (see the class comment of [CollectorStore]).
 */
private class AppEventCap(private val collection: MongoCollection<Document>, private val clock: Clock) {

    @Volatile
    private var cachedAtMillis: Long = Long.MIN_VALUE

    @Volatile
    private var cachedEstimate: Long = 0L

    private val appendedSinceRefresh = AtomicLong()
    private val refreshing = AtomicBoolean(false)

    suspend fun isOverCap(): Boolean {
        val now = clock.millis()
        if (needsRefresh(now) && refreshing.compareAndSet(false, true)) {
            try {
                cachedEstimate = readEstimateOrKeepLastGood()
                cachedAtMillis = now
                appendedSinceRefresh.set(0)
            } finally {
                refreshing.set(false)
            }
        }
        return cachedEstimate + appendedSinceRefresh.get() >= EVENT_CAP_PER_APP
    }

    fun countAppended(eventCount: Int) {
        if (eventCount > 0) {
            appendedSinceRefresh.addAndGet(eventCount.toLong())
        }
    }

    private fun needsRefresh(now: Long): Boolean =
        cachedAtMillis == Long.MIN_VALUE || now - cachedAtMillis >= EVENT_CAP_REFRESH_MILLIS || now < cachedAtMillis

    private suspend fun readEstimateOrKeepLastGood(): Long =
        try {
            collection.estimatedDocumentCount()
        } catch (failure: RuntimeException) {
            log.warn(
                "The collector could not read the event count of design decision D21. " +
                    "It keeps the last known count. The exception class is {}.",
                failure.javaClass.simpleName,
            )
            cachedEstimate
        }
}
