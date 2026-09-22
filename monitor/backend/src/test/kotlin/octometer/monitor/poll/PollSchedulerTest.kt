@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package octometer.monitor.poll

import ch.qos.logback.classic.Level
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import octometer.monitor.captureLogEvents
import octometer.monitor.mongo.MongoReadFailedException
import octometer.monitor.mongo.PollOutcome
import octometer.monitor.mongo.PollTarget
import octometer.monitor.registerTempRoot
import octometer.monitor.registry.ALLOWLISTED_WORD
import octometer.monitor.registry.SecretStore
import octometer.monitor.registry.allowlistedSrvUri
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Issue #17: the poll scheduler of design decision D6. Every test here
// gives an injected Clock, tied to the virtual time of a
// StandardTestDispatcher, and a stub PollCycle. No test uses
// Thread.sleep or a real delay (implementer-rules.md).
//
// Each test gives an in-memory FakePollStore, never a real SqliteDatabase
// (issue #17, decision 7; MAJOR 7 of the Kotlin review). An earlier form
// mixed the virtual clock with the real writer thread of SqliteDatabase.
// That mix raced. A read of next_poll_at could see an extra tick, when
// the real writer thread lagged behind a cancel. SqlitePollStoreTest
// keeps one SQLite-backed test for the write form of the two SQL
// statements.
//
// A test still uses the real SecretStore, because it reads and writes on
// its own real dispatcher (Dispatchers.IO). awaitCondition below suspends
// with `yield`, never with `delay`, so the virtual clock never has to move
// for a real SecretStore call to finish.
class PollSchedulerTest {

    private val root = Files.createTempDirectory("octometer-poll-scheduler-test-").toFile().also { registerTempRoot(it) }
    private val dataDir = File(root, "data").absolutePath

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `the tick loop starts a poll for an app with next_poll_at IS NULL at the first tick`() = runTest {
        val store = FakePollStore()
        val secretStore = SecretStore(dataDir)
        val appId = store.addApp(nextPollAt = null)
        secretStore.put(appId, allowlistedSrvUri())
        val cycle = RecordingPollCycle()

        val scheduler = pollScheduler(store, secretStore, cycle, testScheduler = testScheduler)
        scheduler.start()
        try {
            testScheduler.runCurrent()
            awaitCondition(testScheduler) { cycle.calls.size == 1 }

            assertEquals(PollTarget(appId, "db", "octometer_events", null), cycle.calls.single())
        } finally {
            scheduler.stop()
        }
    }

    @Test
    fun `a due app polls once and its next_poll_at moves by the interval`() = runTest {
        val store = FakePollStore()
        val secretStore = SecretStore(dataDir)
        val appId = store.addApp(nextPollAt = 0L)
        secretStore.put(appId, allowlistedSrvUri())
        val cycle = RecordingPollCycle(outcome = PollOutcome(eventsStored = 3, pagesRead = 1, cursor = "cursor-1"))

        val scheduler = pollScheduler(store, secretStore, cycle, testScheduler = testScheduler, pollIntervalSeconds = 5)
        scheduler.start()
        try {
            testScheduler.runCurrent()
            awaitCondition(testScheduler) { cycle.calls.size == 1 }
        } finally {
            // stop() cancels the tick loop first. No later tick can then
            // move the virtual clock further, while this waits for the
            // one poll in progress to finish (issue #17, decision 1).
            // Only then does a read of next_poll_at see a value with no
            // race on the clock.
            //
            // A finally block: MAJOR 2 of the second Kotlin review. A
            // failed assertion above must still stop the loop, with no
            // hang.
            scheduler.stop()
        }

        assertEquals(5_000L, store.nextPollAtOf(appId), "next_poll_at moves by the interval")
        assertEquals("cursor-1", store.cursorOf(appId), "the cursor moves to the outcome of the cycle")
        assertEquals(1, cycle.calls.size, "the app polls exactly once")
    }

    @Test
    fun `two ticks during one slow poll start no second poll`() = runTest {
        val store = FakePollStore()
        val secretStore = SecretStore(dataDir)
        val appId = store.addApp(nextPollAt = 0L)
        secretStore.put(appId, allowlistedSrvUri())
        val gate = CompletableDeferred<Unit>()
        val cycle = RecordingPollCycle(gate = gate)

        val scheduler = pollScheduler(store, secretStore, cycle, testScheduler = testScheduler, tickIntervalMillis = 1_000)
        scheduler.start()
        try {
            testScheduler.runCurrent()
            awaitCondition(testScheduler) { cycle.calls.size == 1 }

            // A second tick fires while the first poll is still gated.
            // The app must stay at one call: the active-poll map of
            // decision 6 stops a second poll of the same app.
            testScheduler.advanceTimeBy(1_000)
            testScheduler.runCurrent()
            settle(testScheduler)
            assertEquals(1, cycle.calls.size, "a second tick during one slow poll starts no new poll")

            gate.complete(Unit)
        } finally {
            // stop() cancels the loop first. No further tick can then
            // race the virtual clock forward, while this waits for the
            // gated poll to finish (issue #17, decision 1). A finally
            // block: MAJOR 2 of the second Kotlin review.
            scheduler.stop()
        }

        assertEquals(1, cycle.calls.size, "the app polled exactly once in total")
        // The virtual clock already moved to 1 000 ms (the advanceTimeBy
        // call above, for the second tick). The interval adds to that
        // value, not to zero.
        assertEquals(6_000L, store.nextPollAtOf(appId), "next_poll_at moves by the interval")
    }

    @Test
    fun `a new app row polls at the next tick without a restart`() = runTest {
        val store = FakePollStore()
        val secretStore = SecretStore(dataDir)
        val cycle = RecordingPollCycle()

        val scheduler = pollScheduler(store, secretStore, cycle, testScheduler = testScheduler, tickIntervalMillis = 1_000)
        scheduler.start()
        try {
            testScheduler.runCurrent()
            settle(testScheduler)
            assertTrue(cycle.calls.isEmpty(), "an empty app table polls nothing at the first tick")

            val appId = store.addApp(nextPollAt = null)
            secretStore.put(appId, allowlistedSrvUri())

            testScheduler.advanceTimeBy(1_000)
            testScheduler.runCurrent()
            awaitCondition(testScheduler) { cycle.calls.size == 1 }

            assertEquals(appId, cycle.calls.single().appId)
        } finally {
            scheduler.stop()
        }
    }

    @Test
    fun `a deleted app polls no more and its client closes`() = runTest {
        val store = FakePollStore()
        val secretStore = SecretStore(dataDir)
        val appId = store.addApp(nextPollAt = 0L)
        secretStore.put(appId, allowlistedSrvUri())
        val cycle = RecordingPollCycle()
        val closedIds = CopyOnWriteArrayList<Long>()

        val scheduler = pollScheduler(
            store,
            secretStore,
            cycle,
            testScheduler = testScheduler,
            tickIntervalMillis = 1_000,
            closeClient = { closedIds += it },
        )
        scheduler.start()
        try {
            testScheduler.runCurrent()
            awaitCondition(testScheduler) { cycle.calls.size == 1 }

            store.deleteApp(appId)

            testScheduler.advanceTimeBy(1_000)
            testScheduler.runCurrent()
            awaitCondition(testScheduler) { closedIds.contains(appId) }
            settle(testScheduler)

            assertEquals(1, cycle.calls.size, "the deleted app gets no new poll")
            assertEquals(listOf(appId), closedIds, "the scheduler closes the client of the deleted app")
        } finally {
            scheduler.stop()
        }
    }

    // Issue #17, decision 1, first path: a plain exception of the cycle
    // (not a timeout, not a MongoReadFailedException) still moves
    // next_poll_at, with one WARN line. An earlier form let such an
    // exception leave the poll coroutine unhandled. The app then kept
    // its old next_poll_at. The loop re-tried it at every tick (MAJOR 1
    // and MAJOR 3 of the two reviews).
    @Test
    fun `an unexpected exception from the cycle still moves next_poll_at, with one WARN naming its class`() = runTest {
        val store = FakePollStore()
        val secretStore = SecretStore(dataDir)
        val appId = store.addApp(cursor = "old-cursor", nextPollAt = 0L)
        secretStore.put(appId, allowlistedSrvUri())
        val cycle = RecordingPollCycle(failure = IllegalStateException("a probe failure"))

        val scheduler = pollScheduler(store, secretStore, cycle, testScheduler = testScheduler, pollIntervalSeconds = 5)

        val (_, events) = captureLogEvents {
            scheduler.start()
            try {
                testScheduler.runCurrent()
                awaitCondition(testScheduler) { cycle.calls.size == 1 }
            } finally {
                // A finally block: MAJOR 2 of the second Kotlin review.
                scheduler.stop()
            }
        }

        assertEquals("old-cursor", store.cursorOf(appId), "an unexpected exception never moves the cursor")
        assertEquals(5_000L, store.nextPollAtOf(appId), "an unexpected exception still moves next_poll_at")
        val warnings = events.filter { it.level == Level.WARN }
        assertEquals(1, warnings.size, "one WARN line for the failed cycle")
        assertTrue(
            warnings.single().formattedMessage.contains("IllegalStateException"),
            "the WARN line names the exception class",
        )
    }

    // A timeout, or a failed MongoDB read, still moves next_poll_at, with
    // one WARN that names the exception class only. No log line, at any
    // level, holds a part of the connection string (design decision
    // D11).
    @Test
    fun `a failed cycle sets next_poll_at and logs one WARN without a connection string`() = runTest {
        val store = FakePollStore()
        val secretStore = SecretStore(dataDir)
        val appId = store.addApp(cursor = "old-cursor", nextPollAt = 0L)
        secretStore.put(appId, allowlistedSrvUri())
        val cycle = RecordingPollCycle(failure = MongoReadFailedException(RuntimeException("a probe failure")))

        val scheduler = pollScheduler(store, secretStore, cycle, testScheduler = testScheduler, pollIntervalSeconds = 5)

        val (_, events) = captureLogEvents {
            scheduler.start()
            try {
                testScheduler.runCurrent()
                awaitCondition(testScheduler) { cycle.calls.size == 1 }
            } finally {
                // stop() cancels the loop first, so the read below sees
                // a stable next_poll_at. No later tick races the
                // virtual clock forward (issue #17, decision 1). A
                // finally block: MAJOR 2 of the second Kotlin review.
                scheduler.stop()
            }
        }

        assertEquals("old-cursor", store.cursorOf(appId), "a failed cycle never moves the cursor")
        assertEquals(5_000L, store.nextPollAtOf(appId), "a failed cycle still moves next_poll_at")
        val warnings = events.filter { it.level == Level.WARN }
        assertEquals(1, warnings.size, "one WARN line for the failed cycle")
        assertTrue(
            warnings.single().formattedMessage.contains("MongoReadFailedException"),
            "the WARN line names the exception class",
        )
        val combinedText = events.joinToString(" ") { it.formattedMessage }
        assertFalse(combinedText.contains(ALLOWLISTED_WORD), "no log line holds a part of the connection string")
    }

    // Issue #17, decision 1, second path: an app with no stored secret
    // gets one WARN, with no app id, and next_poll_at still moves. An
    // earlier form returned in silence. The loop then read the secret
    // store once each second, for ever, with no line at any level
    // (MAJOR 2 of the two reviews).
    @Test
    fun `an app with no stored secret gets one WARN with no app id, and next_poll_at still moves`() = runTest {
        val store = FakePollStore()
        val secretStore = SecretStore(dataDir)
        val appId = store.addApp(nextPollAt = 0L)
        // No secretStore.put call: the app has no stored secret.
        val cycle = RecordingPollCycle()

        val scheduler = pollScheduler(store, secretStore, cycle, testScheduler = testScheduler, pollIntervalSeconds = 5)

        val (_, events) = captureLogEvents {
            scheduler.start()
            try {
                testScheduler.runCurrent()
                awaitCondition(testScheduler) { store.nextPollAtOf(appId) == 5_000L }
            } finally {
                // A finally block: MAJOR 2 of the second Kotlin review.
                scheduler.stop()
            }
        }

        assertTrue(cycle.calls.isEmpty(), "the cycle never runs with no stored secret")
        val warnings = events.filter { it.level == Level.WARN }
        assertEquals(1, warnings.size, "one WARN line for the missing secret")
        assertEquals(
            "The poll skipped one app with no stored secret.",
            warnings.single().formattedMessage,
            "the WARN line names no app id",
        )
    }

    // Issue #17, decision 1, third path: a failed read of the app list
    // logs one WARN, and the loop ticks again after the normal delay.
    // An earlier form had no try around tick(). That failure left the
    // SupervisorJob's loop coroutine dead. No app polled again (MAJOR 3
    // of the Kotlin review).
    @Test
    fun `a failed read of the app list logs one WARN, and the loop still ticks again`() = runTest {
        val store = FakePollStore()
        val secretStore = SecretStore(dataDir)
        val appId = store.addApp(nextPollAt = null)
        secretStore.put(appId, allowlistedSrvUri())
        val cycle = RecordingPollCycle()
        store.failNextReads(1)

        val scheduler = pollScheduler(store, secretStore, cycle, testScheduler = testScheduler, tickIntervalMillis = 1_000)

        val (_, events) = captureLogEvents {
            scheduler.start()
            try {
                testScheduler.runCurrent()
                settle(testScheduler)
                assertTrue(cycle.calls.isEmpty(), "the failed first tick starts no poll")

                // The next tick, after the normal delay, reads the app
                // list again, and finds the due app.
                testScheduler.advanceTimeBy(1_000)
                testScheduler.runCurrent()
                awaitCondition(testScheduler) { cycle.calls.size == 1 }
            } finally {
                // A finally block: MAJOR 2 of the second Kotlin review.
                scheduler.stop()
            }
        }

        val warnings = events.filter { it.level == Level.WARN }
        assertEquals(1, warnings.size, "one WARN line for the failed tick")
        assertTrue(
            warnings.single().formattedMessage.contains("IllegalStateException"),
            "the WARN line names the exception class",
        )
    }

    // A write of the poll result can itself fail (for example a busy
    // database). This must never leave the poll coroutine with an
    // uncaught exception. A real run of the full suite found the gap: a
    // busy-database probe of a different test class raced with this
    // exact write. A stub cycle throws, so this drives the catch-all of
    // runPollCycle, whose own recordFailure call then fails too.
    @Test
    fun `a write failure of the poll result logs one more WARN, with no uncaught exception`() = runTest {
        val store = FakePollStore()
        val secretStore = SecretStore(dataDir)
        val appId = store.addApp(nextPollAt = 0L)
        secretStore.put(appId, allowlistedSrvUri())
        val cycle = RecordingPollCycle(failure = IllegalStateException("a probe cycle failure"))
        store.failNextFailureWrites(1)

        val scheduler = pollScheduler(store, secretStore, cycle, testScheduler = testScheduler)

        val (_, events) = captureLogEvents {
            scheduler.start()
            try {
                testScheduler.runCurrent()
                awaitCondition(testScheduler) { cycle.calls.size == 1 }
                // The write failure above still lets the loop go on:
                // the next tick retries the write, on the same due app.
                testScheduler.advanceTimeBy(1_000)
                testScheduler.runCurrent()
                awaitCondition(testScheduler) { store.nextPollAtOf(appId) != 0L }
            } finally {
                // A finally block: MAJOR 2 of the second Kotlin review.
                scheduler.stop()
            }
        }

        val warnings = events.filter { it.level == Level.WARN }.map { it.formattedMessage }
        assertTrue(
            warnings.any { it.contains("The poll result write failed.") && it.contains("IllegalStateException") },
            "one WARN line names the write failure",
        )
    }

    @Test
    fun `close stops the loop and waits for the running poll`() = runTest {
        val store = FakePollStore()
        val secretStore = SecretStore(dataDir)
        val appId = store.addApp(nextPollAt = 0L)
        secretStore.put(appId, allowlistedSrvUri())
        val gate = CompletableDeferred<Unit>()
        val order = CopyOnWriteArrayList<String>()
        val cycle = RecordingPollCycle(gate = gate, onRelease = { order += "poll-finished" })

        val scheduler = pollScheduler(store, secretStore, cycle, testScheduler = testScheduler)
        scheduler.start()
        testScheduler.runCurrent()
        awaitCondition(testScheduler) { cycle.calls.size == 1 }

        val stopJob = launch(Dispatchers.Default) {
            scheduler.stop()
            order += "stop-finished"
        }

        try {
            // `stop()` cancels the tick loop, and then waits for the
            // poll that the gate still holds. Pump the virtual
            // scheduler so that cancellation can complete, and confirm
            // stop() has not returned yet, because the poll is still
            // gated.
            repeat(20) {
                testScheduler.advanceUntilIdle()
                yield()
            }
            assertTrue(order.isEmpty(), "stop() must still wait for the poll in progress")
        } finally {
            // A finally block: MAJOR 2 of the second Kotlin review. A
            // failed assertion above must still release the gate, so
            // the gated poll ends and stopJob does not outlive the
            // test.
            gate.complete(Unit)
        }
        // The loop cancel() of stop() races, on a real thread, against
        // the advanceUntilIdle() pumps above. The tick loop can still
        // fire a few more times before that cancel() lands. This waits
        // for any write, not for one exact clock value.
        awaitCondition(testScheduler) { store.nextPollAtOf(appId) != 0L }
        withContext(Dispatchers.Default) { stopJob.join() }

        assertEquals(listOf("poll-finished", "stop-finished"), order, "stop() waits for the running poll first")
    }

    // Issue #17, decision 2 (MAJOR 1 of the security review): a poll that
    // never ends must not hold stop() for ever. stop() waits at most
    // stopGraceMillis, then cancels the poll that has not finished.
    @Test
    fun `stop cancels a poll that never ends, once the grace bound elapses`() = runTest {
        val store = FakePollStore()
        val secretStore = SecretStore(dataDir)
        val appId = store.addApp(nextPollAt = 0L)
        secretStore.put(appId, allowlistedSrvUri())
        // This gate never completes: the poll never ends on its own.
        val gate = CompletableDeferred<Unit>()
        val cycle = RecordingPollCycle(gate = gate)

        // stop() runs its grace bound on a real dispatcher (issue #17,
        // decision 2), so this value is a real wait, not a virtual one.
        // MINOR 5 of the second security review: a small bound here
        // keeps the suite fast, with no real wait of several seconds.
        val scheduler = pollScheduler(
            store,
            secretStore,
            cycle,
            testScheduler = testScheduler,
            stopGraceMillis = 200,
        )
        scheduler.start()
        testScheduler.runCurrent()
        awaitCondition(testScheduler) { cycle.calls.size == 1 }

        val stopJob = launch(Dispatchers.Default) { scheduler.stop() }

        // Pump the virtual scheduler so the grace bound can elapse, and
        // wait for stop() to return on its own, with no real wait.
        repeat(30) {
            testScheduler.advanceUntilIdle()
            yield()
        }
        withContext(Dispatchers.Default) { stopJob.join() }

        assertEquals(1, cycle.calls.size, "the stuck poll ran exactly once")
        assertEquals(0L, store.nextPollAtOf(appId), "a cancelled poll never records a result")
    }
}

private fun pollScheduler(
    pollStore: PollStore,
    secretStore: SecretStore,
    cycle: PollCycle,
    testScheduler: TestCoroutineScheduler,
    pollIntervalSeconds: Long = 5,
    tickIntervalMillis: Long = 1_000,
    stopGraceMillis: Long = 10_000,
    closeClient: (Long) -> Unit = {},
): PollScheduler =
    PollScheduler(
        pollStore = pollStore,
        secretStore = secretStore,
        pollCycle = cycle,
        closeClient = closeClient,
        clock = VirtualClock(testScheduler),
        dispatcher = StandardTestDispatcher(testScheduler),
        pollIntervalSeconds = pollIntervalSeconds,
        tickIntervalMillis = tickIntervalMillis,
        stopGraceMillis = stopGraceMillis,
    )

/** A Clock that reads the virtual time of [scheduler] (issue #17, decision 2). */
private class VirtualClock(
    private val scheduler: TestCoroutineScheduler,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = VirtualClock(scheduler, zone)
    override fun instant(): Instant = Instant.ofEpochMilli(scheduler.currentTime)
}

/**
 * A stub [PollCycle]. With no [gate] it returns [outcome] or throws
 * [failure] at once. With a [gate], it suspends until the test completes
 * that gate, so a test can hold one poll in flight across two ticks.
 */
private class RecordingPollCycle(
    private val outcome: PollOutcome = PollOutcome(eventsStored = 0, pagesRead = 0, cursor = "cursor-1"),
    private val failure: Exception? = null,
    private val gate: CompletableDeferred<Unit>? = null,
    private val onRelease: () -> Unit = {},
) : PollCycle {
    val calls = CopyOnWriteArrayList<PollTarget>()
    private val callCount = AtomicInteger(0)

    override suspend fun run(target: PollTarget, connectionString: String): PollOutcome {
        callCount.incrementAndGet()
        calls += target
        gate?.await()
        onRelease()
        val currentFailure = failure
        if (currentFailure != null) throw currentFailure
        return outcome
    }
}

/**
 * An in-memory [PollStore] (issue #17, decision 7). Every read and every
 * write resolves on the caller's own dispatcher, with no real thread hop.
 * A virtual-time test then sees no race between the test scheduler and a
 * background writer thread (MAJOR 7 of the Kotlin review).
 */
private class FakePollStore(initialRows: List<AppRow> = emptyList()) : PollStore {

    private val rows = LinkedHashMap<Long, AppRow>().apply {
        initialRows.forEach { row -> put(row.appId, row) }
    }
    private var nextId = (initialRows.maxOfOrNull { it.appId } ?: 0L) + 1
    private var readFailuresRemaining = 0
    private var writeFailuresRemaining = 0

    fun addApp(
        database: String = "db",
        collection: String = "octometer_events",
        cursor: String? = null,
        nextPollAt: Long?,
    ): Long {
        val appId = nextId++
        rows[appId] = AppRow(appId, database, collection, cursor, nextPollAt)
        return appId
    }

    fun deleteApp(appId: Long) {
        rows.remove(appId)
    }

    fun cursorOf(appId: Long): String? = rows.getValue(appId).cursor

    fun nextPollAtOf(appId: Long): Long? = rows.getValue(appId).nextPollAt

    /** The next [count] calls to [readApps] throw, and it does not read (issue #17, decision 1). */
    fun failNextReads(count: Int) {
        readFailuresRemaining = count
    }

    /** The next [count] calls to [recordFailure] throw, and it does not write. */
    fun failNextFailureWrites(count: Int) {
        writeFailuresRemaining = count
    }

    override suspend fun readApps(): List<AppRow> {
        if (readFailuresRemaining > 0) {
            readFailuresRemaining -= 1
            throw IllegalStateException("a probe read failure")
        }
        return rows.values.toList()
    }

    override suspend fun writeResult(appId: Long, nextPollAt: Long, cursor: String?) {
        val existing = rows[appId] ?: return
        rows[appId] = existing.copy(nextPollAt = nextPollAt, cursor = cursor)
    }

    override suspend fun recordFailure(appId: Long, nextPollAt: Long) {
        if (writeFailuresRemaining > 0) {
            writeFailuresRemaining -= 1
            throw IllegalStateException("a probe write failure")
        }
        val existing = rows[appId] ?: return
        rows[appId] = existing.copy(nextPollAt = nextPollAt)
    }
}

/**
 * Waits for [check] to become true. Each try first pumps [testScheduler]
 * with `runCurrent`. A continuation that a real call already resumed on
 * the test dispatcher then gets its turn. Each try then yields once, so
 * the real background thread of a SecretStore call gets its turn too.
 * No try uses `delay` or `Thread.sleep` (implementer-rules.md: no test
 * uses a real delay). The real deadline bounds the wait, so a broken
 * test fails instead of a hang.
 */
private suspend fun awaitCondition(
    testScheduler: TestCoroutineScheduler,
    timeoutMillis: Long = 5_000,
    check: suspend () -> Boolean,
) {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000
    while (true) {
        testScheduler.runCurrent()
        if (check()) return
        check(System.nanoTime() < deadline) { "The condition did not become true in time." }
        yield()
    }
}

/** Pumps [testScheduler] a bounded number of times, to let a real background call settle. */
private suspend fun settle(testScheduler: TestCoroutineScheduler, times: Int = 20) {
    repeat(times) {
        testScheduler.runCurrent()
        yield()
    }
}
