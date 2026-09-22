package octometer.monitor.retention

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import octometer.monitor.captureErrorLogEvents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Issue #59, step 4: the purge job runs at the start, then again each 24
// hours. This test controls the wait between two runs with a channel, so
// it needs no real sleep and no wait of 24 hours (implementer-rules.md,
// "do not sleep in a test").
class RetentionPurgeJobTest {

    @Test
    fun `start runs the action once at once`() = runBlocking {
        val runCount = AtomicInteger(0)
        val runSignal = Channel<Unit>(Channel.UNLIMITED)
        val neverReturns = Channel<Unit>()
        val job = RetentionPurgeJob(
            wait = { neverReturns.receive() },
            action = {
                runCount.incrementAndGet()
                runSignal.send(Unit)
            },
        )

        job.start()
        try {
            withTimeout(5_000) { runSignal.receive() }
            assertEquals(1, runCount.get())
        } finally {
            job.stop()
        }
    }

    @Test
    fun `the action runs again once the wait call returns`() = runBlocking {
        val runCount = AtomicInteger(0)
        val runSignal = Channel<Unit>(Channel.UNLIMITED)
        val waitGate = Channel<Unit>()
        val job = RetentionPurgeJob(
            wait = { waitGate.receive() },
            action = {
                runCount.incrementAndGet()
                runSignal.send(Unit)
            },
        )

        job.start()
        try {
            withTimeout(5_000) { runSignal.receive() }
            assertEquals(1, runCount.get())

            waitGate.send(Unit)

            withTimeout(5_000) { runSignal.receive() }
            assertEquals(2, runCount.get())
        } finally {
            job.stop()
        }
    }

    @Test
    fun `stop cancels the job, and the action never runs again`() = runBlocking {
        val runCount = AtomicInteger(0)
        val runSignal = Channel<Unit>(Channel.UNLIMITED)
        val waitGate = Channel<Unit>()
        val job = RetentionPurgeJob(
            wait = { waitGate.receive() },
            action = {
                runCount.incrementAndGet()
                runSignal.send(Unit)
            },
        )

        job.start()
        withTimeout(5_000) { runSignal.receive() }
        assertEquals(1, runCount.get())

        // stop() cancels the loop while it waits on waitGate. Nothing
        // receives from waitGate now, so this test never sends to it; a
        // send here would wait forever, because the channel is a
        // rendezvous with no reader left (implementer-rules.md: do not
        // sleep in a test, and the same reasoning rules out a wait with
        // no chance of an answer).
        job.stop()

        assertEquals(1, runCount.get(), "the action must not run after stop")
    }

    // backend-brief.md, lesson 2: a cancellation must reach the loop again,
    // never turn into a logged "purge run failed" line. This cancels the
    // job while the action is in the middle of a run, not while it waits.
    @Test
    fun `stop while the action runs writes no error log line`() = runBlocking {
        val runSignal = Channel<Unit>(Channel.UNLIMITED)
        val midFlightGate = Channel<Unit>()
        val job = RetentionPurgeJob(
            action = {
                runSignal.send(Unit)
                midFlightGate.receive()
            },
        )

        job.start()
        withTimeout(5_000) { runSignal.receive() }

        val (_, errorEvents) = captureErrorLogEvents { job.stop() }

        assertTrue(errorEvents.isEmpty(), "a normal stop must write no ERROR line")
    }

    @Test
    fun `stop is safe when the job never started`() {
        val job = RetentionPurgeJob(action = {})
        job.stop()
    }

    // MAJOR 2 of backend-brief.md: a failure of one run must not leak a
    // CancellationException catch, and it must not stop the loop early.
    @Test
    fun `a failed run does not stop the loop`() = runBlocking {
        val runCount = AtomicInteger(0)
        val runSignal = Channel<Unit>(Channel.UNLIMITED)
        val waitGate = Channel<Unit>()
        val job = RetentionPurgeJob(
            wait = { waitGate.receive() },
            action = {
                val count = runCount.incrementAndGet()
                runSignal.send(Unit)
                if (count == 1) error("a broken run, for this test only")
            },
        )

        job.start()
        try {
            withTimeout(5_000) { runSignal.receive() }
            waitGate.send(Unit)
            withTimeout(5_000) { runSignal.receive() }
            assertEquals(2, runCount.get())
        } finally {
            job.stop()
        }
    }
}
