package octometer.monitor.poll

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import octometer.monitor.mongo.STATUS_ERROR
import octometer.monitor.mongo.STATUS_UNAUTHORIZED
import octometer.monitor.mongo.STATUS_UNREACHABLE

/**
 * The pure functions of issue #28: [failureStatus] (the failed-cycle-
 * count rule of the maintainer's decision 2) and [backoffDelayMillis]
 * (the backoff of design decision D6 and the maintainer's decision 3).
 * Neither function suspends or reads a clock, so each test here runs
 * with no virtual time and no coroutine. [PollSchedulerTest] covers the
 * same rules again, with the virtual clock of a full tick loop.
 */
class PollBackoffTest {

    // --- failureStatus ---

    @Test
    fun `UNREACHABLE keeps the old status on the first failed cycle`() {
        val status = failureStatus(STATUS_UNREACHABLE, oldStatus = "OK", failuresBefore = 0)

        assertEquals("OK", status, "the first failed cycle must keep the old status")
    }

    @Test
    fun `UNREACHABLE keeps a null old status on the first failed cycle`() {
        val status = failureStatus(STATUS_UNREACHABLE, oldStatus = null, failuresBefore = 0)

        assertEquals(null, status, "a never-polled app has no old status to keep")
    }

    @Test
    fun `UNREACHABLE applies from the second failed cycle in sequence`() {
        val status = failureStatus(STATUS_UNREACHABLE, oldStatus = "OK", failuresBefore = 1)

        assertEquals(STATUS_UNREACHABLE, status, "the second failed cycle in sequence must show UNREACHABLE")
    }

    @Test
    fun `UNAUTHORIZED applies at once, with no failed-cycle-count rule`() {
        assertEquals(STATUS_UNAUTHORIZED, failureStatus(STATUS_UNAUTHORIZED, oldStatus = "OK", failuresBefore = 0))
        assertEquals(STATUS_UNAUTHORIZED, failureStatus(STATUS_UNAUTHORIZED, oldStatus = "OK", failuresBefore = 5))
    }

    @Test
    fun `ERROR applies at once, with no failed-cycle-count rule`() {
        assertEquals(STATUS_ERROR, failureStatus(STATUS_ERROR, oldStatus = "OK", failuresBefore = 0))
        assertEquals(STATUS_ERROR, failureStatus(STATUS_ERROR, oldStatus = "OK", failuresBefore = 5))
    }

    // --- backoffDelayMillis ---

    @Test
    fun `the delay is the interval on the first failed cycle, within the jitter of 10 percent`() {
        val delay = backoffDelayMillis(intervalSeconds = 60, failuresBefore = 0, random = Random(42))

        assertInRange(delay, baseMillis = 60_000L)
    }

    @Test
    fun `the delay doubles on the second failed cycle, within the jitter of 10 percent`() {
        val delay = backoffDelayMillis(intervalSeconds = 60, failuresBefore = 1, random = Random(42))

        assertInRange(delay, baseMillis = 120_000L)
    }

    @Test
    fun `the delay is 4 times the interval on the third failed cycle, within the jitter of 10 percent`() {
        val delay = backoffDelayMillis(intervalSeconds = 60, failuresBefore = 2, random = Random(42))

        assertInRange(delay, baseMillis = 240_000L)
    }

    @Test
    fun `the delay never rises above 300 seconds, within the jitter of 10 percent`() {
        val delay = backoffDelayMillis(intervalSeconds = 60, failuresBefore = 10, random = Random(42))

        assertInRange(delay, baseMillis = 300_000L)
    }

    // The acceptance criterion of issue #28: a check over 100 draws.
    // Each draw of a seeded Random must land inside the jitter band of
    // 10 percent, for the interval delay and for the capped delay
    // alike. MAJOR 1 of the Kotlin review: this test must also fail
    // when the jitter goes away. A jitter of exactly 0.0 on every draw
    // gives the same base delay 100 times in a row, so the "not all
    // equal" check below catches that defect too. I multiplied the
    // jitter draw of backoffDelayMillis by 0.0 for a moment. This test
    // then failed on the "not all equal" check, for both delay lists. I
    // then restored the file.
    @Test
    fun `100 draws of a seeded Random each stay inside the jitter of 10 percent, and are not all equal`() {
        val random = Random(1234)
        val intervalDelays = mutableListOf<Long>()
        val cappedDelays = mutableListOf<Long>()
        repeat(100) {
            val intervalDelay = backoffDelayMillis(intervalSeconds = 60, failuresBefore = 0, random = random)
            assertInRange(intervalDelay, baseMillis = 60_000L)
            intervalDelays += intervalDelay

            val cappedDelay = backoffDelayMillis(intervalSeconds = 60, failuresBefore = 10, random = random)
            assertInRange(cappedDelay, baseMillis = 300_000L)
            cappedDelays += cappedDelay
        }
        assertTrue(intervalDelays.toSet().size > 1, "the jitter must vary the interval delay across 100 draws")
        assertTrue(cappedDelays.toSet().size > 1, "the jitter must vary the capped delay across 100 draws")
    }

    private fun assertInRange(actualMillis: Long, baseMillis: Long) {
        val lowBound = (baseMillis * 0.9).toLong()
        val highBound = (baseMillis * 1.1).toLong()
        assertTrue(
            actualMillis in lowBound..highBound,
            "expected $actualMillis inside [$lowBound, $highBound] for a base of $baseMillis",
        )
    }
}
