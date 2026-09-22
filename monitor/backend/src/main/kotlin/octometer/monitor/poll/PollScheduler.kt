package octometer.monitor.poll

import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.withTimeoutOrNull
import octometer.monitor.mongo.MongoReadFailedException
import octometer.monitor.mongo.PollOutcome
import octometer.monitor.mongo.PollTarget
import octometer.monitor.registry.SecretStore
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("octometer.monitor.poll.PollScheduler")

/** The tick interval of design decision D6: one second. */
private const val DEFAULT_TICK_INTERVAL_MILLIS = 1_000L

/** The bound of [PollScheduler.stop] (issue #17, decision 2): 10 seconds. */
private const val DEFAULT_STOP_GRACE_MILLIS = 10_000L

/**
 * One poll cycle of one app (issue #17, decision 2). The production
 * value is [octometer.monitor.mongo.MongoAppReader.pollOnce]. A test
 * gives a stub, so it needs no MongoDB server and no Docker container.
 */
fun interface PollCycle {
    suspend fun run(target: PollTarget, connectionString: String): PollOutcome
}

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
 * `next_poll_at` moves by [pollIntervalSeconds] after each cycle, on a
 * success and on a failure alike (issue #17, decision 6). Issue #28
 * adds the backoff of a failed cycle. Issue #29 adds the wake rule of a
 * resumed process. Neither one is part of this class.
 *
 * Each epoch value of the `app` table, and of this class, is a count of
 * milliseconds since 1970. This is the unit of the column `created_at`
 * and of [PollTarget] (issue #17, decision 9).
 *
 * [pollStore] is the store seam of decision 7 (Kotlin review, MAJOR 7).
 * [SqlitePollStore] is the production value. A test gives an in-memory
 * fake instead, so no test mixes virtual time with the real writer
 * thread of `SqliteDatabase`.
 */
class PollScheduler(
    private val pollStore: PollStore,
    private val secretStore: SecretStore,
    private val pollCycle: PollCycle,
    private val closeClient: (Long) -> Unit,
    private val clock: Clock,
    dispatcher: CoroutineDispatcher,
    private val pollIntervalSeconds: Long,
    private val tickIntervalMillis: Long = DEFAULT_TICK_INTERVAL_MILLIS,
    private val stopGraceMillis: Long = DEFAULT_STOP_GRACE_MILLIS,
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
     * poll that is still in progress. The wait has a bound of
     * [stopGraceMillis] (issue #17, decision 2; MAJOR 1 of the security
     * review).
     *
     * A poll that has not finished by that bound gets one cancel. A
     * stuck MongoDB read can then never hold the stop call for ever.
     *
     * The scope of this scheduler then cancels too (MINOR 2 of the
     * Kotlin review). No leftover job of it can start a new poll.
     */
    suspend fun stop() {
        loopJob?.cancelAndJoin()
        val running = activePolls.values.toList()
        val allJoinedInTime = withTimeoutOrNull(stopGraceMillis) {
            running.forEach { it.join() }
        }
        if (allJoinedInTime == null) {
            running.forEach { it.cancelAndJoin() }
        }
        supervisor.cancel()
    }

    /**
     * The tick loop. One failed tick logs one WARN and the loop goes on
     * (issue #17, decision 1). A real cancellation of this coroutine
     * still propagates, unchanged, so a `stop` call still ends the loop
     * at once.
     */
    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            try {
                tick()
            } catch (cancellation: CancellationException) {
                // Lesson 2 of the backend brief: a real cancellation of
                // this coroutine must still propagate. A false one, from
                // a still-active coroutine, is a failed tick instead.
                if (!currentCoroutineContext().isActive) throw cancellation
                log.warn("The poll tick failed. {}", cancellation.javaClass.simpleName)
            } catch (failure: Exception) {
                log.warn("The poll tick failed. {}", failure.javaClass.simpleName)
            }
            delay(tickIntervalMillis)
        }
    }

    /**
     * Reads the app list (issue #17, decision 5). It then does two
     * things:
     * - It closes the client of each app that is gone since the last
     *   tick.
     * - It starts a poll for each due app with no active poll.
     */
    private suspend fun tick() {
        val rows = pollStore.readApps()
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
     * on while the poll runs (issue #17, decision 6).
     */
    private fun startPoll(row: AppRow) {
        val target = PollTarget(row.appId, row.database, row.collection, row.cursor)
        val job = scope.launch { runPollCycle(row.appId, target) }
        activePolls[row.appId] = job
        job.invokeOnCompletion { activePolls.remove(row.appId, job) }
    }

    /**
     * Runs one poll cycle. It then records the result (issue #17,
     * decision 6). The secret read sits inside this same try (issue
     * #17, decision 1). An app with no stored secret gets one WARN,
     * with no app id. Its `next_poll_at` still moves. The loop then
     * reads the secret store once each interval, not once each second.
     *
     * A timeout of the cycle, or a failed MongoDB read, still moves
     * `next_poll_at` by the interval. The cursor never changes on that
     * path. Issue #28 adds the backoff of this case.
     *
     * A real cancellation of the scheduler propagates unchanged. The
     * guard below re-throws a [CancellationException] only when this
     * coroutine is no longer active (lesson 2 of the backend brief). A
     * false one, from a still-active coroutine, moves `next_poll_at`
     * like each other failed cycle. The last catch guards against each
     * other exception, for example a SQLite failure of
     * `EventStore.commitPage`. One bad cycle can then never leave this
     * app stuck at its old `next_poll_at` for ever.
     */
    private suspend fun runPollCycle(appId: Long, target: PollTarget) {
        try {
            val connectionString = secretStore.get(appId)
            if (connectionString == null) {
                log.warn("The poll skipped one app with no stored secret.")
                recordFailure(appId)
                return
            }
            val outcome = pollCycle.run(target, connectionString)
            recordSuccess(appId, outcome)
        } catch (timeout: TimeoutCancellationException) {
            log.warn("The poll cycle failed. {}", timeout.javaClass.simpleName)
            recordFailure(appId)
        } catch (readFailure: MongoReadFailedException) {
            log.warn("The poll cycle failed. {}", readFailure.javaClass.simpleName)
            recordFailure(appId)
        } catch (cancellation: CancellationException) {
            // Lesson 2 of the backend brief: re-throw only a real
            // cancellation of this coroutine. A false one, from a
            // still-active coroutine, is a failed cycle instead.
            if (!currentCoroutineContext().isActive) throw cancellation
            log.warn("The poll cycle failed. {}", cancellation.javaClass.simpleName)
            recordFailure(appId)
        } catch (failure: Exception) {
            log.warn("The poll cycle failed. {}", failure.javaClass.simpleName)
            recordFailure(appId)
        }
    }

    /**
     * Writes a good outcome. A write failure here (for example a busy
     * database) logs one WARN and stops there. It never reaches
     * [runPollCycle]'s own catch-all, so it can never trigger a second
     * write attempt of its own.
     */
    private suspend fun recordSuccess(appId: Long, outcome: PollOutcome) {
        val nextPollAt = clock.millis() + pollIntervalSeconds * 1000
        writeSafely { pollStore.writeResult(appId, nextPollAt, outcome.cursor) }
    }

    /** Writes a failed outcome. See [recordSuccess] for the write-failure guard. */
    private suspend fun recordFailure(appId: Long) {
        val nextPollAt = clock.millis() + pollIntervalSeconds * 1000
        writeSafely { pollStore.recordFailure(appId, nextPollAt) }
    }

    private suspend fun writeSafely(write: suspend () -> Unit) {
        try {
            write()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            log.warn("The poll result write failed. {}", failure.javaClass.simpleName)
        }
    }
}
