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
import kotlin.random.Random
import octometer.monitor.mongo.MongoReadFailedException
import octometer.monitor.mongo.PollOutcome
import octometer.monitor.mongo.PollTarget
import octometer.monitor.mongo.STATUS_ERROR
import octometer.monitor.registry.SecretStore
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("octometer.monitor.poll.PollScheduler")

/** The tick interval of design decision D6: one second. */
private const val DEFAULT_TICK_INTERVAL_MILLIS = 1_000L

/** The bound of [PollScheduler.stop] (issue #17, decision 2): 10 seconds. */
private const val DEFAULT_STOP_GRACE_MILLIS = 10_000L

/** The wait of the wake rule (design decision D7, issue #29): 15 seconds. */
private const val DEFAULT_WAKE_DELAY_MILLIS = 15_000L

/**
 * The threshold of the wake rule (design decision D7, issue #29): more
 * than two poll intervals, never two ticks of one second.
 */
private const val WAKE_THRESHOLD_INTERVALS = 2

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
 * `next_poll_at` moves by [pollIntervalSeconds] after a success (issue
 * #17, decision 6). A failed cycle moves it by the backoff of design
 * decision D6 and the maintainer's decision 3 of issue #28: `interval *
 * 2^failures`, capped at 300 seconds, with a jitter of 10 percent from
 * [random].
 *
 * Each epoch value of the `app` table, and of this class, is a count of
 * milliseconds since 1970. This is the unit of the column `created_at`
 * and of [PollTarget] (issue #17, decision 9).
 *
 * [pollStore] is the store seam of decision 7 (Kotlin review, MAJOR 7).
 * [SqlitePollStore] is the production value. A test gives an in-memory
 * fake instead, so no test mixes virtual time with the real writer
 * thread of `SqliteDatabase`.
 *
 * [random] is the jitter source of issue #28. A test gives a seeded
 * [Random], so the backoff delay stays reproducible.
 *
 * The wake rule (design decision D7, issue #29): a wake from sleep
 * moves the wall clock far ahead of its own monotonic timer, on
 * Windows past the point where the network is ready. Each tick
 * compares [clock] with the planned time of that tick. A gap of more
 * than two poll intervals writes one INFO line, then waits
 * [wakeDelayMillis] before it goes on. The wait is a [delay] on the
 * injected dispatcher, so a virtual-time test covers it too.
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
    private val wakeDelayMillis: Long = DEFAULT_WAKE_DELAY_MILLIS,
    private val random: Random = Random,
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

    // The planned time of the wake rule (design decision D7, issue #29).
    // start() sets it to the clock value of the first tick, before the
    // loop coroutine can run, so the very first tick never looks like a
    // resume. Only the loop coroutine reads or writes it after that.
    @Volatile
    private var plannedTickAt: Long = 0L

    /** Starts the tick loop. Call this at most one time. */
    fun start() {
        check(loopJob == null) { "The poll scheduler already started." }
        plannedTickAt = clock.millis()
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
        // MAJOR 1 of the third Kotlin review: activePolls is a
        // ConcurrentHashMap. Iterable.toList() reads size first, then
        // calls iterator().next(). A poll that finishes, and removes
        // its own entry, between those two reads leaves the iterator
        // empty, and next() throws NoSuchElementException. snapshot()
        // takes one copy instead, through the constructor of ArrayList,
        // which never races the map this way.
        val running = snapshot(activePolls.values)
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
     *
     * [checkWakeRule] runs first, before either one (design decision D7,
     * issue #29). A resumed process then closes no client, and starts no
     * poll, until the wait of the wake rule ends.
     */
    private suspend fun tick() {
        checkWakeRule()

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

    /**
     * The wake rule of design decision D7 and issue #29. It compares
     * [clock] with [plannedTickAt], the planned time of this tick.
     *
     * A gap of more than two poll intervals (never two ticks of one
     * second) means the process resumed from sleep: the wall clock
     * jumped ahead of the loop's own monotonic delay. This writes one
     * INFO line with the lateness in seconds, then waits
     * [wakeDelayMillis] before the rest of this tick runs. The wait is a
     * [delay] on the injected dispatcher, so a virtual-time test covers
     * it, with no real wait and no `Thread.sleep`.
     *
     * A gap of two intervals or less is normal tick drift, not a
     * resume; the rest of this tick runs at once.
     *
     * [plannedTickAt] then moves to the clock value of that same
     * moment, the end of the wait on a resume, or the start of this
     * tick otherwise. A resume never moves it by the plain tick
     * interval: the wait itself would then look like a second resume,
     * at the very next tick.
     */
    private suspend fun checkWakeRule() {
        val now = clock.millis()
        val thresholdMillis = WAKE_THRESHOLD_INTERVALS * pollIntervalSeconds * 1_000
        val latenessMillis = now - plannedTickAt
        if (latenessMillis > thresholdMillis) {
            log.info("resume detected. {}", latenessMillis / 1_000)
            delay(wakeDelayMillis)
            plannedTickAt = clock.millis()
        } else {
            plannedTickAt = now + tickIntervalMillis
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
        val job = scope.launch { runPollCycle(row, target) }
        activePolls[row.appId] = job
        job.invokeOnCompletion { activePolls.remove(row.appId, job) }
    }

    /**
     * Runs one poll cycle. It then records the result (issue #17,
     * decision 6). The secret read sits inside this same try (issue
     * #17, decision 1). An app with no stored secret gets one WARN,
     * with no app id, and one failed-cycle record with the status
     * `ERROR` (issue #28: this path throws no exception, so the map of
     * design decision D8 does not apply). The loop then reads the
     * secret store once each interval, not once each second.
     *
     * A timeout of the cycle, or a failed MongoDB read, moves
     * `next_poll_at` by the backoff of issue #28, decision 3, never by
     * the plain interval. The cursor never changes on that path.
     * [readFailure] already carries the mapped status and the command
     * error code of design decision D8 ([MongoReadFailedException]);
     * each other failure counts as `ERROR` with its own class name (the
     * maintainer's decision 3).
     *
     * A real cancellation of the scheduler propagates unchanged. The
     * guard below re-throws a [CancellationException] only when this
     * coroutine is no longer active (lesson 2 of the backend brief). A
     * false one, from a still-active coroutine, is a failed cycle
     * instead. The last catch guards against each other exception, for
     * example a SQLite failure of `EventStore.commitPage`. One bad
     * cycle can then never leave this app stuck at its old
     * `next_poll_at` for ever.
     */
    private suspend fun runPollCycle(row: AppRow, target: PollTarget) {
        val appId = row.appId
        try {
            val connectionString = secretStore.get(appId)
            if (connectionString == null) {
                log.warn("The poll skipped one app with no stored secret.")
                recordFailure(row, STATUS_ERROR, "no stored secret")
                return
            }
            val outcome = pollCycle.run(target, connectionString)
            recordSuccess(appId, outcome)
        } catch (timeout: TimeoutCancellationException) {
            log.warn("The poll cycle failed. {}", timeout.javaClass.simpleName)
            recordFailure(row, STATUS_ERROR, timeout.javaClass.simpleName)
        } catch (readFailure: MongoReadFailedException) {
            log.warn("The poll cycle failed. {}", readFailure.javaClass.simpleName)
            val lastError = readFailure.code?.toString() ?: readFailure.javaClass.simpleName
            recordFailure(row, readFailure.status, lastError)
        } catch (cancellation: CancellationException) {
            // Lesson 2 of the backend brief: re-throw only a real
            // cancellation of this coroutine. A false one, from a
            // still-active coroutine, is a failed cycle instead.
            if (!currentCoroutineContext().isActive) throw cancellation
            log.warn("The poll cycle failed. {}", cancellation.javaClass.simpleName)
            recordFailure(row, STATUS_ERROR, cancellation.javaClass.simpleName)
        } catch (failure: Exception) {
            log.warn("The poll cycle failed. {}", failure.javaClass.simpleName)
            recordFailure(row, STATUS_ERROR, failure.javaClass.simpleName)
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

    /**
     * Writes a failed outcome (issue #28, design decision D6, D8). It
     * applies the failed-cycle-count rule of the maintainer's decision
     * 2 to [candidateStatus], against the status and the failure count
     * of [row] from the start of this tick. It computes the backoff
     * delay of decision 3 from that same failure count, the count
     * before this cycle. See [recordSuccess] for the write-failure
     * guard.
     */
    private suspend fun recordFailure(row: AppRow, candidateStatus: String, lastError: String) {
        val now = clock.millis()
        val status = failureStatus(candidateStatus, row.status, row.consecutiveFailures)
        val delayMillis = backoffDelayMillis(pollIntervalSeconds, row.consecutiveFailures, random)
        val nextPollAt = now + delayMillis
        writeSafely { pollStore.recordFailure(row.appId, status, lastError, nextPollAt, now) }
    }

    private suspend fun writeSafely(write: suspend () -> Unit) {
        try {
            write()
        } catch (cancellation: CancellationException) {
            // MINOR 3 of the third Kotlin review, lesson 2 of the
            // backend brief: re-throw only a real cancellation of this
            // coroutine. A false one, from a still-active coroutine, is
            // a failed write instead.
            if (!currentCoroutineContext().isActive) throw cancellation
            log.warn("The poll result write failed. {}", cancellation.javaClass.simpleName)
        } catch (failure: Exception) {
            log.warn("The poll result write failed. {}", failure.javaClass.simpleName)
        }
    }
}

/**
 * Copies [source] into one new list. MAJOR 1 of the third Kotlin
 * review: `Iterable.toList()` reads `size`, then calls
 * `iterator().next()`. A `Collection` that changes between those two
 * reads, for example the value view of a `ConcurrentHashMap`, can then
 * throw `NoSuchElementException` for no real error. `ArrayList`'s own
 * constructor calls `toArray()` instead, one call that a concurrent
 * collection writes to stay correct under a concurrent change.
 */
internal fun <V> snapshot(source: Collection<V>): List<V> = ArrayList(source)
