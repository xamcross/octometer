package octometer.monitor.collect

import com.mongodb.kotlin.client.coroutine.MongoClient
import java.sql.Connection
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import octometer.monitor.store.SqliteDatabase
import org.bson.Document
import org.slf4j.LoggerFactory

/** The cluster-wide write stop bound of design decision D50: 450 MB. */
private const val WRITE_STOP_BYTES = 450L * 1024 * 1024

/** The refresh interval of the `dbStats` sum, design decision D50: one minute. */
private const val REFRESH_MILLIS = 60_000L

private val log = LoggerFactory.getLogger("octometer.monitor.collect.WriteStopGate")

/**
 * The cluster-wide write stop of design decision D50 (issue #297): one
 * `dbStats` sum over every `app_*` database named in the registry (the
 * brief review of 2026-10-01, B4: no `listDatabases`), one time each
 * minute, against 450 MB of `storageSize` plus `indexSize`. Above the
 * bound, the collector drops each write with 204, and `GET /api/health`
 * reports `writeStopped: true`.
 *
 * A failed `dbStats` read, or a cluster with no URI yet, keeps the last
 * known state and writes one warning; this gate fails open (never
 * stopped) until a read succeeds, because a `dbStats` outage must not by
 * itself halt every app's collection.
 */
class WriteStopGate(
    private val database: SqliteDatabase,
    private val client: () -> MongoClient?,
    private val clock: Clock = Clock.systemUTC(),
) {

    @Volatile
    private var cachedAtMillis: Long = Long.MIN_VALUE

    @Volatile
    private var cachedStopped: Boolean = false

    private val refreshing = AtomicBoolean(false)

    /** True when the cluster's `app_*` storage is at or above [WRITE_STOP_BYTES]. */
    suspend fun isWriteStopped(): Boolean {
        val now = clock.millis()
        if (needsRefresh(now) && refreshing.compareAndSet(false, true)) {
            try {
                cachedStopped = refresh()
                cachedAtMillis = now
            } finally {
                refreshing.set(false)
            }
        }
        return cachedStopped
    }

    private fun needsRefresh(now: Long): Boolean =
        cachedAtMillis == Long.MIN_VALUE || now - cachedAtMillis >= REFRESH_MILLIS || now < cachedAtMillis

    private suspend fun refresh(): Boolean {
        val mongoClient = client() ?: return false
        return try {
            val databaseNames = database.read { reader -> readDatabaseNames(reader) }
            var totalBytes = 0L
            for (name in databaseNames) {
                val stats = mongoClient.getDatabase(name).runCommand(Document("dbStats", 1))
                totalBytes += numberField(stats, "storageSize") + numberField(stats, "indexSize")
            }
            totalBytes >= WRITE_STOP_BYTES
        } catch (failure: RuntimeException) {
            log.warn(
                "The write stop could not read dbStats of the cluster. It keeps the last known state. " +
                    "The exception class is {}.",
                failure.javaClass.simpleName,
            )
            cachedStopped
        }
    }

    private fun numberField(document: Document, field: String): Long =
        (document[field] as? Number)?.toLong() ?: 0L

    private fun readDatabaseNames(reader: Connection): List<String> =
        reader.prepareStatement("SELECT database_name FROM app WHERE database_name IS NOT NULL AND database_name != ''").use { select ->
            select.executeQuery().use { result ->
                val names = mutableListOf<String>()
                while (result.next()) {
                    names += result.getString("database_name")
                }
                names
            }
        }
}
