package octometer.monitor.poll

import java.sql.Connection
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import octometer.monitor.mongo.MongoReadFailedException
import octometer.monitor.mongo.PollOutcome
import octometer.monitor.mongo.PollTarget
import octometer.monitor.registry.SecretStore
import octometer.monitor.store.SqliteDatabase
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("octometer.monitor.poll.PollScheduler")

/** The tick interval of design decision D6: one second. */
private const val DEFAULT_TICK_INTERVAL_MILLIS = 1_000L

/**
 * One poll cycle of one app (issue #17, decision 2). The production
 * value is [octometer.monitor.mongo.MongoAppReader.pollOnce]. A test
 * gives a stub, so it needs no MongoDB server and no Docker container.
 */
fun interface PollCycle {
    suspend fun run(target: PollTarget, connectionString: String): PollOutcome
}

/** One row of the app table, read at one tick (issue #17, decision 5). */
private data class AppRow(
    val appId: Long,
    val database: String,
    val collection: String,
    val cursor: String?,
    val nextPollAt: Long?,
)

/**
 * The poll scheduler of design decision D6 and issue #17. One tick loop
 * reads the app list, and it starts a poll for each app that is due,
 * with no active poll already. [MonitorServices] starts one instance at
 * `open`, and stops it at `close`, the same form as the daily backup job
 * and the retention purge job.
 *
 * [clock], [dispatcher], and [pollCycle] are each an injected value
 * (issue #17, decision 2). A test gives a stub [pollCycle], and a
 * `Clock` that reads the virtual time of a `StandardTestDispatcher`, so
 * no test needs a real wait.
 *
 * The scheduler adds no second `withTimeout` (issue #17, decision 3).
 * [octometer.monitor.mongo.MongoAppReader.pollOnce] already holds the
 * one timeout of design decision D6, inside the injected [pollCycle].
 *
 * [next_poll_at] moves by [pollIntervalSeconds] after each cycle, on a
 * success and on a failure alike (issue #17, decision 6). Issue #28
 * adds the backoff of a failed cycle, and issue #29 adds the wake rule
 * of a resumed process. Neither one is part of this class.
 *
 * Each epoch value of the `app` table, and of this class, is a count of
 * milliseconds since 1970, the unit of the column `created_at` and of
 * [PollTarget] (issue #17, decision 9).
 */
class PollScheduler(
    private val database: SqliteDatabase,
    private val secretStore: SecretStore,
    private val pollCycle: PollCycle,
    private val closeClient: (Long) -> Unit,
    private val clock: Clock,
    dispatcher: CoroutineDispatcher,
    private val pollIntervalSeconds: Long,
    private val tickIntervalMillis: Long = DEFAULT_TICK_INTERVAL_MILLIS,
) {

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + dispatcher)

    // One entry for each app id with a poll in progress (issue #17,
    // decision 6). A concurrent map, because the tick loop reads it on
    // its own coroutine, and a finished poll removes its own entry from
    // a different coroutine.
    private val activePolls = ConcurrentHashMap<Long, Job>()

    private var loopJob: Job? = null

    @Volatile
    private var knownAppIds: Set<Long> = emptySet()

    /** Starts the tick loop. Call this at most one time. */
    fun start() {
        check(loopJob == null) { "The poll scheduler already started." }
        loopJob = scope.launch { loop() }
    }

    /**
     * Stops the tick loop, so no new poll starts. It then waits for each
     * poll that is still in progress, instead of a cancel of that poll,
     * so a caller never sees a half-written cursor (issue #17, decision
     * 1 and the acceptance test of a `close` call).
     */
    suspend fun stop() {
        loopJob?.cancelAndJoin()
        activePolls.values.toList().forEach { it.join() }
    }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            tick()
            delay(tickIntervalMillis)
        }
    }

    /**
     * Reads the app list in one `read { }` block (issue #17, decision
     * 5), closes the client of each app that is gone since the last
     * tick, and starts a poll for each due app with no active poll.
     */
    private suspend fun tick() {
        val rows = database.read { reader -> readAppRows(reader) }
        val currentAppIds = rows.map { it.appId }.toSet()
        closeGoneClients(currentAppIds)
        knownAppIds = currentAppIds

        val now = clock.millis()
        for (row in rows) {
            if (!isDue(row, now)) continue
            if (activePolls.containsKey(row.appId)) continue
            startPoll(row)
        }
    }

    private fun isDue(row: AppRow, now: Long): Boolean =
        row.nextPollAt == null || row.nextPollAt <= now

    /**
     * Closes the reader client of each app id of the last tick that is
     * absent from [currentAppIds] now: a deleted app (issue #17,
     * decision 7). The app row is already gone, thus this tick starts
     * no poll for it either.
     */
    private fun closeGoneClients(currentAppIds: Set<Long>) {
        for (goneAppId in knownAppIds - currentAppIds) {
            closeClient(goneAppId)
        }
    }

    /**
     * Starts one poll cycle as a child coroutine, so the tick loop goes
     * on while the poll runs (issue #17, decision 6). The connection
     * string of [row] comes from the secret store here, only for a due
     * app, never at the read of the app list.
     */
    private fun startPoll(row: AppRow) {
        val target = PollTarget(row.appId, row.database, row.collection, row.cursor)
        val job = scope.launch {
            val connectionString = secretStore.get(row.appId)
            if (connectionString == null) return@launch
            runPollCycle(row.appId, target, connectionString)
        }
        activePolls[row.appId] = job
        job.invokeOnCompletion { activePolls.remove(row.appId, job) }
    }

    /**
     * Runs one poll cycle, and records the result with one `write { }`
     * block (issue #17, decision 6). A timeout of the cycle, or a failed
     * MongoDB read, still moves `next_poll_at` by the interval, with no
     * change of the cursor. Issue #28 adds the backoff of this case.
     *
     * A real cancellation of the scheduler propagates unchanged: this
     * catches [TimeoutCancellationException] and [MongoReadFailedException]
     * only, never the plain [kotlinx.coroutines.CancellationException] of
     * a `stop` call (lesson 2 of the backend brief).
     */
    private suspend fun runPollCycle(appId: Long, target: PollTarget, connectionString: String) {
        try {
            val outcome = pollCycle.run(target, connectionString)
            recordSuccess(appId, outcome)
        } catch (timeout: TimeoutCancellationException) {
            log.warn("The poll cycle failed. {}", timeout.javaClass.simpleName)
            recordFailure(appId)
        } catch (readFailure: MongoReadFailedException) {
            log.warn("The poll cycle failed. {}", readFailure.javaClass.simpleName)
            recordFailure(appId)
        }
    }

    private suspend fun recordSuccess(appId: Long, outcome: PollOutcome) {
        val nextPollAt = clock.millis() + pollIntervalSeconds * 1000
        database.write { writer -> writeSuccess(writer, appId, nextPollAt, outcome.cursor) }
    }

    private suspend fun recordFailure(appId: Long) {
        val nextPollAt = clock.millis() + pollIntervalSeconds * 1000
        database.write { writer -> writeFailure(writer, appId, nextPollAt) }
    }
}

private fun readAppRows(reader: Connection): List<AppRow> =
    reader.createStatement().use { statement ->
        statement.executeQuery(
            "SELECT id, database_name, collection_name, cursor, next_poll_at FROM app",
        ).use { result ->
            val rows = mutableListOf<AppRow>()
            while (result.next()) {
                val nextPollAt = result.getLong("next_poll_at").takeUnless { result.wasNull() }
                rows += AppRow(
                    appId = result.getLong("id"),
                    database = result.getString("database_name"),
                    collection = result.getString("collection_name"),
                    cursor = result.getString("cursor"),
                    nextPollAt = nextPollAt,
                )
            }
            rows
        }
    }

// A deleted app gives zero updated rows here, and this never throws for
// that case (issue #17, decision 7).
private fun writeSuccess(writer: Connection, appId: Long, nextPollAt: Long, cursor: String?) {
    writer.prepareStatement("UPDATE app SET next_poll_at = ?, cursor = ? WHERE id = ?").use { update ->
        update.setLong(1, nextPollAt)
        update.setString(2, cursor)
        update.setLong(3, appId)
        update.executeUpdate()
    }
}

private fun writeFailure(writer: Connection, appId: Long, nextPollAt: Long) {
    writer.prepareStatement("UPDATE app SET next_poll_at = ? WHERE id = ?").use { update ->
        update.setLong(1, nextPollAt)
        update.setLong(2, appId)
        update.executeUpdate()
    }
}
