package octometer.monitor.retention

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import octometer.monitor.captureErrorLogEvents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Issue #59, step 4: the purge job runs at the start. It runs again each 24
// hours. This test controls the wait between two runs with a channel. It
// needs no real sleep and no wait of 24 hours (implementer-rules.md,
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

            // Correction round 1, decision 4: this send stays inside a
            // timeout. A regression that stops the loop then fails the
            // test, instead of a hang with no result.
            withTimeout(5_000) { waitGate.send(Unit) }

            withTimeout(5_000) { runSignal.receive() }
            assertEquals(2, runCount.get())
        } finally {
            job.stop()
        }
    }

    // Correction round 1, decision 3: the 24-hour interval is a value that
    // this test can find out. A different value fails this test. The wait
    // function records its argument, and the assertion holds the literal
    // number, not the production constant.
    @Test
    fun `start waits 24 hours between two runs`() = runBlocking {
        val waited = Channel<Long>(Channel.UNLIMITED)
        val runSignal = Channel<Unit>(Channel.UNLIMITED)
        val waitGate = Channel<Unit>()
        val job = RetentionPurgeJob(
            wait = { millis ->
                waited.send(millis)
                waitGate.receive()
            },
            action = { runSignal.send(Unit) },
        )

        job.start()
        try {
            withTimeout(5_000) { runSignal.receive() }
            val millisWaited = withTimeout(5_000) { waited.receive() }
            assertEquals(24L * 60 * 60 * 1000, millisWaited, "the gap between two runs is 24 hours")
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
        // receives from waitGate now, so this test never sends to it. A
        // send here would wait forever, because the channel is a
        // rendezvous with no reader left (implementer-rules.md: do not
        // sleep in a test, and the same reasoning rules out a wait with
        // no chance of an answer).
        job.stop()

        assertEquals(1, runCount.get(), "the action must not run after stop")
    }

    // backend-brief.md, lesson 2: a cancellation must reach the loop again.
    // It must never turn into a logged "purge run failed" line. This
    // cancels the job while the action runs, not while it waits.
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

    // Correction round 1, MINOR (both reviews): a start() call after a
    // stop() call must not run a job that never fires. It must throw a
    // clear error, instead of a silent no-op.
    @Test
    fun `start after stop throws, instead of a silent restart`() {
        val job = RetentionPurgeJob(action = {})
        job.start()
        job.stop()

        assertFailsWith<IllegalStateException> { job.start() }
    }

    // MAJOR 2 of backend-brief.md: a failure of one run must not leak a
    // CancellationException catch. It must not stop the loop early.
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
            withTimeout(5_000) { waitGate.send(Unit) }
            withTimeout(5_000) { runSignal.receive() }
            assertEquals(2, runCount.get())
        } finally {
            job.stop()
        }
    }

    // Correction round 1, decision 2 (MAJOR of both reviews): the Kotlin
    // type CancellationException is an alias of the JVM class
    // java.util.concurrent.CancellationException. A store call can throw
    // that exact class while the job stays active. The loop must log one
    // line and run again, not die for the life of the process.
    @Test
    fun `a foreign CancellationException from the action logs one line, and the loop runs again`() = runBlocking {
        val runCount = AtomicInteger(0)
        val runSignal = Channel<Unit>(Channel.UNLIMITED)
        val waitGate = Channel<Unit>()
        val job = RetentionPurgeJob(
            wait = { waitGate.receive() },
            action = {
                val count = runCount.incrementAndGet()
                runSignal.send(Unit)
                if (count == 1) throw java.util.concurrent.CancellationException("a store task was cancelled")
            },
        )

        val (_, errorEvents) = captureErrorLogEvents {
            job.start()
            withTimeout(5_000) { runSignal.receive() }
            withTimeout(5_000) { waitGate.send(Unit) }
            withTimeout(5_000) { runSignal.receive() }
        }

        job.stop()
        assertEquals(2, runCount.get(), "the loop must run a second time")
        assertEquals(1, errorEvents.size, "one ERROR line for the foreign cancellation")
    }
}
