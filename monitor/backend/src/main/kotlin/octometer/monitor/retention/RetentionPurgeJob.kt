package octometer.monitor.retention

import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("octometer.monitor.retention.RetentionPurgeJob")

// Issue #59, step 4: the purge runs at the start, then again each 24 hours.
private const val PURGE_INTERVAL_MILLIS = 24L * 60 * 60 * 1000

private const val THREAD_NAME = "octometer-retention-purge"

/**
 * The scheduled purge of issue #59, step 4 (D15). [start] runs [action]
 * one time at once. It runs [action] again each 24 hours, on its own
 * thread. [stop] cancels the run, then joins it, with no fixed time
 * bound. The batch loop of [RetentionPurge.purgeOnce] checks the cancel
 * between two batches, so the join ends soon, at the next batch
 * boundary.
 *
 * [wait] holds the gap between two runs. The production code keeps the
 * default, the real coroutine `delay`. A test gives its own [wait]
 * function. A test of this class then needs no real wait of 24 hours.
 *
 * A failed [action] never stops the loop. The next run still starts
 * after the usual gap. A real cancellation of the job still ends the
 * loop. A foreign `CancellationException` from inside [action] does
 * not end the loop (correction round 1, lesson 2 of the backend
 * brief).
 */
class RetentionPurgeJob(
    private val wait: suspend (Long) -> Unit = { millis -> delay(millis) },
    private val action: suspend () -> Unit,
) {
    private val dispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, THREAD_NAME) }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null
    private var stopped = false

    fun start() {
        check(!stopped) { "The purge job already stopped once. It does not start again." }
        check(job == null) { "The purge job is already started." }
        job = scope.launch {
            while (isActive) {
                runOnce()
                wait(PURGE_INTERVAL_MILLIS)
            }
        }
    }

    /**
     * Cancels the run, then joins it, with no fixed time bound. This
     * method is safe when [start] never ran. The join ends at the next
     * batch boundary of [RetentionPurge.purgeOnce], because that loop
     * checks the cancel between two batches. After [stop] runs once,
     * [start] cannot run again.
     */
    fun stop() {
        val currentJob = job
        if (currentJob != null) {
            runBlocking { currentJob.cancelAndJoin() }
        }
        scope.cancel()
        dispatcher.close()
        job = null
        stopped = true
    }

    private suspend fun runOnce() {
        try {
            action()
        } catch (cancellation: CancellationException) {
            // The Kotlin type is an alias of the JVM class
            // java.util.concurrent.CancellationException. A store call
            // can throw that exact class while this coroutine stays
            // active. Only a real cancellation of this job may end the
            // loop. Throw the exception again only then.
            if (!currentCoroutineContext().isActive) throw cancellation
            log.error("The purge run failed. {}", cancellation.javaClass.simpleName)
        } catch (failure: Throwable) {
            log.error("The purge run failed. {}", failure.javaClass.simpleName)
        }
    }
}
