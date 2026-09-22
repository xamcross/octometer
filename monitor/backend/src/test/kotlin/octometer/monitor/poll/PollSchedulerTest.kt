@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package octometer.monitor.poll

import ch.qos.logback.classic.Level
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.Types
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
import octometer.monitor.store.SqliteDatabase
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
// A test still uses the real SqliteDatabase and the real SecretStore
// (decision 5 of the brief), because both read and write on their own
// real dispatchers. awaitCondition below suspends with `yield`, never
// with `delay`, so the virtual clock never has to move for a real store
// call to finish.
class PollSchedulerTest {

    private val root = Files.createTempDirectory("octometer-poll-scheduler-test-").toFile().also { registerTempRoot(it) }
    private val dataDir = File(root, "data").absolutePath

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `the tick loop starts a poll for an app with next_poll_at IS NULL at the first tick`() = runTest {
        val database = SqliteDatabase.open(dataDir)
        try {
            val secretStore = SecretStore(dataDir)
            val appId = insertApp(database, "demo", nextPollAt = null)
            secretStore.put(appId, allowlistedSrvUri())
            val cycle = RecordingPollCycle()

            val scheduler = pollScheduler(database, secretStore, cycle, testScheduler = testScheduler)
            scheduler.start()
            try {
                testScheduler.runCurrent()
                awaitCondition(testScheduler) { cycle.calls.size == 1 }

                assertEquals(PollTarget(appId, "db", "octometer_events", null), cycle.calls.single())
            } finally {
                scheduler.stop()
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `a due app polls once and its next_poll_at moves by the interval`() = runTest {
        val database = SqliteDatabase.open(dataDir)
        try {
            val secretStore = SecretStore(dataDir)
            val appId = insertApp(database, "demo", nextPollAt = 0L)
            secretStore.put(appId, allowlistedSrvUri())
            val cycle = RecordingPollCycle(outcome = PollOutcome(eventsStored = 3, pagesRead = 1, cursor = "cursor-1"))

            val scheduler = pollScheduler(database, secretStore, cycle, testScheduler = testScheduler, pollIntervalSeconds = 5)
            scheduler.start()
            testScheduler.runCurrent()
            awaitCondition(testScheduler) { cycle.calls.size == 1 }

            // stop() cancels the tick loop first, so no later tick can move
            // the virtual clock further while this waits for the one poll
            // in progress to finish (issue #17, decision 1). Only then does
            // a read of next_poll_at see a value with no race on the clock.
            scheduler.stop()

            assertEquals(5_000L, readNextPollAt(database, appId), "next_poll_at moves by the interval")
            assertEquals("cursor-1", readCursor(database, appId), "the cursor moves to the outcome of the cycle")
            assertEquals(1, cycle.calls.size, "the app polls exactly once")
        } finally {
            database.close()
        }
    }

    @Test
    fun `two ticks during one slow poll start no second poll`() = runTest {
        val database = SqliteDatabase.open(dataDir)
        try {
            val secretStore = SecretStore(dataDir)
            val appId = insertApp(database, "demo", nextPollAt = 0L)
            secretStore.put(appId, allowlistedSrvUri())
            val gate = CompletableDeferred<Unit>()
            val cycle = RecordingPollCycle(gate = gate)

            val scheduler = pollScheduler(database, secretStore, cycle, testScheduler = testScheduler, tickIntervalMillis = 1_000)
            scheduler.start()
            testScheduler.runCurrent()
            awaitCondition(testScheduler) { cycle.calls.size == 1 }

            // A second tick fires while the first poll is still gated. The
            // app must stay at one call: the active-poll map of decision 6
            // stops a second poll of the same app.
            testScheduler.advanceTimeBy(1_000)
            testScheduler.runCurrent()
            settle(testScheduler)
            assertEquals(1, cycle.calls.size, "a second tick during one slow poll starts no new poll")

            gate.complete(Unit)
            // stop() cancels the loop first, so no further tick can race the
            // virtual clock forward while this waits for the gated poll to
            // finish (issue #17, decision 1).
            scheduler.stop()

            assertEquals(1, cycle.calls.size, "the app polled exactly once in total")
            // The virtual clock already moved to 1 000 ms (the advanceTimeBy
            // call above, for the second tick), so the interval adds to
            // that value, not to zero.
            assertEquals(6_000L, readNextPollAt(database, appId), "next_poll_at moves by the interval")
        } finally {
            database.close()
        }
    }

    @Test
    fun `a new app row polls at the next tick without a restart`() = runTest {
        val database = SqliteDatabase.open(dataDir)
        try {
            val secretStore = SecretStore(dataDir)
            val cycle = RecordingPollCycle()

            val scheduler = pollScheduler(database, secretStore, cycle, testScheduler = testScheduler, tickIntervalMillis = 1_000)
            scheduler.start()
            try {
                testScheduler.runCurrent()
                settle(testScheduler)
                assertTrue(cycle.calls.isEmpty(), "an empty app table polls nothing at the first tick")

                val appId = insertApp(database, "demo", nextPollAt = null)
                secretStore.put(appId, allowlistedSrvUri())

                testScheduler.advanceTimeBy(1_000)
                testScheduler.runCurrent()
                awaitCondition(testScheduler) { cycle.calls.size == 1 }

                assertEquals(appId, cycle.calls.single().appId)
            } finally {
                scheduler.stop()
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `a deleted app polls no more and its client closes`() = runTest {
        val database = SqliteDatabase.open(dataDir)
        try {
            val secretStore = SecretStore(dataDir)
            val appId = insertApp(database, "demo", nextPollAt = 0L)
            secretStore.put(appId, allowlistedSrvUri())
            val cycle = RecordingPollCycle()
            val closedIds = CopyOnWriteArrayList<Long>()

            val scheduler = pollScheduler(
                database,
                secretStore,
                cycle,
                testScheduler = testScheduler,
                tickIntervalMillis = 1_000,
                closeClient = { closedIds += it },
            )
            scheduler.start()
            try {
                testScheduler.runCurrent()
                // The in-memory call count never depends on the virtual
                // clock, unlike a read of next_poll_at (issue #17, decision
                // 6: a delete race is exactly what decision 7 covers: the
                // eventual write of this poll must find zero rows, and
                // throw nothing).
                awaitCondition(testScheduler) { cycle.calls.size == 1 }

                deleteApp(database, appId)

                testScheduler.advanceTimeBy(1_000)
                testScheduler.runCurrent()
                awaitCondition(testScheduler) { closedIds.contains(appId) }
                settle(testScheduler)

                assertEquals(1, cycle.calls.size, "the deleted app gets no new poll")
                assertEquals(listOf(appId), closedIds, "the scheduler closes the client of the deleted app")
            } finally {
                scheduler.stop()
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `a failed cycle sets next_poll_at and logs one WARN without a connection string`() = runTest {
        val database = SqliteDatabase.open(dataDir)
        try {
            val secretStore = SecretStore(dataDir)
            val appId = insertApp(database, "demo", nextPollAt = 0L, cursor = "old-cursor")
            secretStore.put(appId, allowlistedSrvUri())
            val cycle = RecordingPollCycle(failure = MongoReadFailedException(RuntimeException("a probe failure")))

            val scheduler = pollScheduler(database, secretStore, cycle, testScheduler = testScheduler, pollIntervalSeconds = 5)

            val (_, events) = captureLogEvents {
                scheduler.start()
                testScheduler.runCurrent()
                awaitCondition(testScheduler) { cycle.calls.size == 1 }
                // stop() cancels the loop first, so the read below sees a
                // stable next_poll_at, with no later tick racing the
                // virtual clock forward (issue #17, decision 1).
                scheduler.stop()
            }

            assertEquals("old-cursor", readCursor(database, appId), "a failed cycle never moves the cursor")
            assertEquals(5_000L, readNextPollAt(database, appId), "a failed cycle still moves next_poll_at")
            val warnings = events.filter { it.level == Level.WARN }
            assertEquals(1, warnings.size, "one WARN line for the failed cycle")
            assertTrue(
                warnings.single().formattedMessage.contains("MongoReadFailedException"),
                "the WARN line names the exception class",
            )
            val combinedText = events.joinToString(" ") { it.formattedMessage }
            assertFalse(combinedText.contains(ALLOWLISTED_WORD), "no log line holds a part of the connection string")
        } finally {
            database.close()
        }
    }

    @Test
    fun `close stops the loop and waits for the running poll`() = runTest {
        val database = SqliteDatabase.open(dataDir)
        try {
            val secretStore = SecretStore(dataDir)
            val appId = insertApp(database, "demo", nextPollAt = 0L)
            secretStore.put(appId, allowlistedSrvUri())
            val gate = CompletableDeferred<Unit>()
            val order = CopyOnWriteArrayList<String>()
            val cycle = RecordingPollCycle(gate = gate, onRelease = { order += "poll-finished" })

            val scheduler = pollScheduler(database, secretStore, cycle, testScheduler = testScheduler)
            scheduler.start()
            testScheduler.runCurrent()
            awaitCondition(testScheduler) { cycle.calls.size == 1 }

            val stopJob = launch(Dispatchers.Default) {
                scheduler.stop()
                order += "stop-finished"
            }

            // `stop()` cancels the tick loop, and then waits for the poll
            // that the gate still holds. Pump the virtual scheduler so
            // that cancellation can complete, and confirm stop() has not
            // returned yet, because the poll is still gated.
            repeat(20) {
                testScheduler.advanceUntilIdle()
                yield()
            }
            assertTrue(order.isEmpty(), "stop() must still wait for the poll in progress")

            gate.complete(Unit)
            awaitCondition(testScheduler) { readNextPollAt(database, appId) == 5_000L }
            withContext(Dispatchers.Default) { stopJob.join() }

            assertEquals(listOf("poll-finished", "stop-finished"), order, "stop() waits for the running poll first")
        } finally {
            database.close()
        }
    }
}

private fun pollScheduler(
    database: SqliteDatabase,
    secretStore: SecretStore,
    cycle: PollCycle,
    testScheduler: TestCoroutineScheduler,
    pollIntervalSeconds: Long = 5,
    tickIntervalMillis: Long = 1_000,
    closeClient: (Long) -> Unit = {},
): PollScheduler =
    PollScheduler(
        database = database,
        secretStore = secretStore,
        pollCycle = cycle,
        closeClient = closeClient,
        clock = VirtualClock(testScheduler),
        dispatcher = StandardTestDispatcher(testScheduler),
        pollIntervalSeconds = pollIntervalSeconds,
        tickIntervalMillis = tickIntervalMillis,
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
 * Waits for [check] to become true. Each try first pumps [testScheduler]
 * with `runCurrent`, so a continuation that a real store call already
 * resumed on the test dispatcher gets its turn, then it yields once, so
 * the real background thread of that store call gets its turn too. No
 * try uses `delay` or `Thread.sleep` (implementer-rules.md: no test uses
 * a real delay). The real deadline bounds the wait, so a broken test
 * fails instead of a hang.
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

private suspend fun insertApp(
    database: SqliteDatabase,
    name: String,
    nextPollAt: Long?,
    cursor: String? = null,
    databaseName: String = "db",
    collectionName: String = "octometer_events",
): Long =
    database.write { writer ->
        writer.prepareStatement(
            "INSERT INTO app (name, database_name, collection_name, created_at, next_poll_at, cursor) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
        ).use { insert ->
            insert.setString(1, name)
            insert.setString(2, databaseName)
            insert.setString(3, collectionName)
            insert.setLong(4, 0L)
            if (nextPollAt != null) insert.setLong(5, nextPollAt) else insert.setNull(5, Types.INTEGER)
            insert.setString(6, cursor)
            insert.executeUpdate()
        }
        lastInsertId(writer)
    }

private fun lastInsertId(writer: Connection): Long =
    writer.createStatement().use { statement ->
        statement.executeQuery("SELECT last_insert_rowid()").use { result ->
            check(result.next()) { "No id after the insert." }
            result.getLong(1)
        }
    }

private suspend fun deleteApp(database: SqliteDatabase, appId: Long) {
    database.write { writer ->
        writer.prepareStatement("DELETE FROM app WHERE id = ?").use { delete ->
            delete.setLong(1, appId)
            delete.executeUpdate()
        }
    }
}

private suspend fun readNextPollAt(database: SqliteDatabase, appId: Long): Long? =
    database.read { reader -> readLongColumn(reader, "next_poll_at", appId) }

private suspend fun readCursor(database: SqliteDatabase, appId: Long): String? =
    database.read { reader ->
        reader.prepareStatement("SELECT cursor FROM app WHERE id = ?").use { select ->
            select.setLong(1, appId)
            select.executeQuery().use { result ->
                check(result.next()) { "No app row for id $appId." }
                result.getString(1)
            }
        }
    }

private fun readLongColumn(reader: Connection, column: String, appId: Long): Long? =
    reader.prepareStatement("SELECT $column FROM app WHERE id = ?").use { select ->
        select.setLong(1, appId)
        select.executeQuery().use { result ->
            check(result.next()) { "No app row for id $appId." }
            val value = result.getLong(1)
            if (result.wasNull()) null else value
        }
    }
