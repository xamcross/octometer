package octometer.kit.core.ingest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of {@link AnonymousMinuteLimiter} against design decision D43 and
 * the acceptance criteria of issue #116.
 */
class AnonymousMinuteLimiterTest {

    private static final Instant START = Instant.parse("2026-09-22T10:00:00.000Z");

    @Test
    void request301OfOneKeyInsideOneMinuteGivesTheRateLimitResultAndRequest300Passes() {
        MutableClock clock = new MutableClock(START);
        AnonymousMinuteLimiter limiter = new AnonymousMinuteLimiter(clock, 300, 900, 120);

        for (int i = 1; i <= 300; i++) {
            assertTrue(limiter.checkRequest("203.0.113.9"), "Request " + i + " of 300 must pass.");
        }
        assertFalse(limiter.checkRequest("203.0.113.9"));
    }

    @Test
    void aRequestOfTheSameKeyAfterTheWindowEndsPasses() {
        MutableClock clock = new MutableClock(START);
        AnonymousMinuteLimiter limiter = new AnonymousMinuteLimiter(clock, 300, 900, 120);

        for (int i = 1; i <= 301; i++) {
            limiter.checkRequest("203.0.113.9");
        }
        assertFalse(limiter.checkRequest("203.0.113.9"));

        clock.advance(Duration.ofSeconds(60));

        assertTrue(limiter.checkRequest("203.0.113.9"));
    }

    @Test
    void clickEntry901OfOneKeyInsideOneMinuteGivesTheRateLimitResult() {
        MutableClock clock = new MutableClock(START);
        AnonymousMinuteLimiter limiter = new AnonymousMinuteLimiter(clock, 300, 900, 120);

        for (int i = 1; i <= 900; i++) {
            assertTrue(limiter.checkEntries("203.0.113.9", 1, 0), "Click entry " + i + " of 900 must pass.");
        }
        assertFalse(limiter.checkEntries("203.0.113.9", 1, 0));
    }

    @Test
    void sessionStart121OfOneKeyInsideOneMinuteGivesTheRateLimitResult() {
        MutableClock clock = new MutableClock(START);
        AnonymousMinuteLimiter limiter = new AnonymousMinuteLimiter(clock, 300, 900, 120);

        for (int i = 1; i <= 120; i++) {
            assertTrue(limiter.checkEntries("203.0.113.9", 0, 1), "Session start " + i + " of 120 must pass.");
        }
        assertFalse(limiter.checkEntries("203.0.113.9", 0, 1));
    }

    @Test
    void aSecondKeyStaysUnaffectedByTheFirstKeyAtItsLimit() {
        MutableClock clock = new MutableClock(START);
        AnonymousMinuteLimiter limiter = new AnonymousMinuteLimiter(clock, 1, 1, 1);

        assertTrue(limiter.checkRequest("203.0.113.9"));
        assertFalse(limiter.checkRequest("203.0.113.9"));

        assertTrue(limiter.checkRequest("198.51.100.2"));
    }

    @Test
    void theClickEntryCounterAndTheSessionStartCounterOfOneKeyStayIndependent() {
        MutableClock clock = new MutableClock(START);
        AnonymousMinuteLimiter limiter = new AnonymousMinuteLimiter(clock, 300, 1, 1);

        assertTrue(limiter.checkEntries("203.0.113.9", 1, 0));
        assertFalse(limiter.checkEntries("203.0.113.9", 1, 0));

        // The session-start counter of the same key is a separate count,
        // so it still has its own full budget.
        assertTrue(limiter.checkEntries("203.0.113.9", 0, 1));
    }

    @Test
    void theThreeCountersOfOneKeyResetTogetherAfter60Seconds() {
        MutableClock clock = new MutableClock(START);
        AnonymousMinuteLimiter limiter = new AnonymousMinuteLimiter(clock, 1, 1, 1);

        assertTrue(limiter.checkRequest("203.0.113.9"));
        assertFalse(limiter.checkRequest("203.0.113.9"));
        assertTrue(limiter.checkEntries("203.0.113.9", 1, 0));
        assertFalse(limiter.checkEntries("203.0.113.9", 1, 0));
        assertTrue(limiter.checkEntries("203.0.113.9", 0, 1));
        assertFalse(limiter.checkEntries("203.0.113.9", 0, 1));

        clock.advance(Duration.ofSeconds(60));

        assertTrue(limiter.checkRequest("203.0.113.9"));
        assertTrue(limiter.checkEntries("203.0.113.9", 1, 0));
        assertTrue(limiter.checkEntries("203.0.113.9", 0, 1));
    }

    @Test
    void aClockThatStepsBackwardsStillResetsTheCounterAtTheOriginalWindowEnd() {
        MutableClock clock = new MutableClock(START);
        AnonymousMinuteLimiter limiter = new AnonymousMinuteLimiter(clock, 1, 1, 1);

        assertTrue(limiter.checkRequest("203.0.113.9"));
        assertFalse(limiter.checkRequest("203.0.113.9"));

        // An NTP correction, or a resume from an older snapshot, steps the
        // clock backwards. With no guard, the counter would hold its
        // window forever, the form of AnonymousDailyCap's own guard.
        clock.stepBackTo(START.minus(Duration.ofHours(1)));

        assertTrue(limiter.checkRequest("203.0.113.9"));
    }

    @Test
    void theKeyMapEvictsTheLeastRecentKeyAt20001KeysAndHolds20000() {
        MutableClock clock = new MutableClock(START);
        AnonymousMinuteLimiter limiter = new AnonymousMinuteLimiter(clock, 1, 1, 1);

        for (int i = 0; i < AnonymousMinuteLimiter.MAX_KEYS; i++) {
            assertTrue(limiter.checkRequest("key-" + i), "key-" + i + " must pass.");
        }
        assertEquals(AnonymousMinuteLimiter.MAX_KEYS, limiter.keyCount());

        // key-0 is now the oldest key. One more distinct key evicts it.
        assertTrue(limiter.checkRequest("one-key-too-many"));
        assertEquals(AnonymousMinuteLimiter.MAX_KEYS, limiter.keyCount());

        // key-0 lost its counter through the eviction, so it starts a
        // fresh count, and one more request of it still passes.
        assertTrue(limiter.checkRequest("key-0"));
    }

    @Test
    void aRejectionWritesAMaximumOfOneLogLineForTheWindowWithNoKeyInIt() {
        CapturingLoggerFinder.clear();
        MutableClock clock = new MutableClock(START);
        AnonymousMinuteLimiter limiter = new AnonymousMinuteLimiter(clock, 1, 900, 120);

        for (int i = 0; i < 10; i++) {
            limiter.checkRequest("198.51.100.7");
        }

        assertEquals(1, CapturingLoggerFinder.messages().size());
        String message = CapturingLoggerFinder.messages().peek();
        assertFalse(message.contains("198.51.100.7"));
    }

    @Test
    void aNonPositiveRequestLimitThrows() {
        MutableClock clock = new MutableClock(START);
        assertThrows(IllegalArgumentException.class, () -> new AnonymousMinuteLimiter(clock, 0, 1, 1));
    }

    @Test
    void aNonPositiveClickEntryLimitThrows() {
        MutableClock clock = new MutableClock(START);
        assertThrows(IllegalArgumentException.class, () -> new AnonymousMinuteLimiter(clock, 1, 0, 1));
    }

    @Test
    void aNonPositiveSessionStartLimitThrows() {
        MutableClock clock = new MutableClock(START);
        assertThrows(IllegalArgumentException.class, () -> new AnonymousMinuteLimiter(clock, 1, 1, 0));
    }

    @Test
    void aNegativeClickEntryCountThrows() {
        MutableClock clock = new MutableClock(START);
        AnonymousMinuteLimiter limiter = new AnonymousMinuteLimiter(clock, 1, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> limiter.checkEntries("key-1", -1, 0));
    }

    @Test
    void aNegativeSessionStartCountThrows() {
        MutableClock clock = new MutableClock(START);
        AnonymousMinuteLimiter limiter = new AnonymousMinuteLimiter(clock, 1, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> limiter.checkEntries("key-1", 0, -1));
    }

    /** A {@link Clock} that a test can move forward, for a window test. */
    private static final class MutableClock extends Clock {
        private volatile Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        /** Moves this clock to an earlier instant, for a backward-step test. */
        void stepBackTo(Instant earlierInstant) {
            instant = earlierInstant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("This test clock always uses UTC.");
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
