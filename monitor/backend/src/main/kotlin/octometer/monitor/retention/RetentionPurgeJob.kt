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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("octometer.monitor.retention.RetentionPurgeJob")

// Issue #59, step 4: the purge runs at the start, then each 24 hours.
private const val PURGE_INTERVAL_MILLIS = 24L * 60 * 60 * 1000

private const val THREAD_NAME = "octometer-retention-purge"

/**
 * The scheduled purge of issue #59, step 4 (D15). [start] runs [action]
 * one time at once, then again each 24 hours, on its own thread. [stop]
 * cancels that run and waits for it to end, so a purge in progress
 * finishes, or stops at its next suspension point, before [stop] returns.
 *
 * [wait] holds the gap between two runs. The production code keeps the
 * default, the real coroutine `delay`. A test gives its own [wait]
 * function, so a test of this class needs no real wait of 24 hours.
 *
 * A failed [action] never stops the loop. The next run still starts
 * after the usual gap. The catch here never swallows a real
 * cancellation; it always throws that one again.
 */
class RetentionPurgeJob(
    private val wait: suspend (Long) -> Unit = { millis -> delay(millis) },
    private val action: suspend () -> Unit,
) {
    private val dispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, THREAD_NAME) }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null

    fun start() {
        check(job == null) { "The purge job is already started." }
        job = scope.launch {
            while (isActive) {
                runOnce()
                wait(PURGE_INTERVAL_MILLIS)
            }
        }
    }

    /** Cancels the run and closes the thread. Safe when [start] never ran. */
    fun stop() {
        val currentJob = job
        if (currentJob != null) {
            runBlocking { currentJob.cancelAndJoin() }
        }
        scope.cancel()
        dispatcher.close()
        job = null
    }

    private suspend fun runOnce() {
        try {
            action()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            log.error("The purge run failed. {}", failure.javaClass.simpleName)
        }
    }
}
