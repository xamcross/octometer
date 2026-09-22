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
 * Tests of {@link AnonymousDailyCap} against design decision D43 and the
 * acceptance criteria of issue #117.
 */
class AnonymousDailyCapTest {

    private static final Instant START = Instant.parse("2026-09-22T10:00:00.000Z");

    @Test
    void aBatchAboveTheGlobalCapDrops() {
        MutableClock clock = new MutableClock(START);
        AnonymousDailyCap cap = new AnonymousDailyCap(clock, 10, 100);

        assertTrue(cap.check("key-1", 10));
        assertFalse(cap.check("key-2", 1));
    }

    @Test
    void aBatchAboveTheKeyCapDropsThatKeyAndASecondKeyStillPasses() {
        MutableClock clock = new MutableClock(START);
        AnonymousDailyCap cap = new AnonymousDailyCap(clock, 1_000, 5);

        assertTrue(cap.check("key-1", 5));
        assertFalse(cap.check("key-1", 1));
        assertTrue(cap.check("key-2", 5));
    }

    @Test
    void aDroppedBatchCountsNothing() {
        MutableClock clock = new MutableClock(START);
        AnonymousDailyCap cap = new AnonymousDailyCap(clock, 5, 5);

        assertFalse(cap.check("key-1", 6));
        // The dropped batch of 6 added nothing, so 5 still fits under the
        // cap of 5.
        assertTrue(cap.check("key-1", 5));
    }

    @Test
    void oneWarnLineEachHourWithAFixedClockThatSteps() {
        CapturingLoggerFinder.clear();
        MutableClock clock = new MutableClock(START);
        AnonymousDailyCap cap = new AnonymousDailyCap(clock, 1, 100);
        cap.check("key-1", 1);

        cap.check("key-2", 1);
        clock.advance(Duration.ofMinutes(30));
        cap.check("key-3", 1);

        assertEquals(1, CapturingLoggerFinder.messages().size());

        clock.advance(Duration.ofMinutes(31));
        cap.check("key-4", 1);

        assertEquals(2, CapturingLoggerFinder.messages().size());
    }

    @Test
    void theWarnLineHoldsNoKeyNoAddressAndNoUserAgent() {
        CapturingLoggerFinder.clear();
        MutableClock clock = new MutableClock(START);
        AnonymousDailyCap cap = new AnonymousDailyCap(clock, 1, 100);
        cap.check("203.0.113.9", 1);

        cap.check("203.0.113.9", 1);

        String message = CapturingLoggerFinder.messages().peek();
        assertFalse(message.contains("203.0.113.9"));
    }

    @Test
    void theGlobalCounterResetsAfter24Hours() {
        MutableClock clock = new MutableClock(START);
        AnonymousDailyCap cap = new AnonymousDailyCap(clock, 1, 100);

        assertTrue(cap.check("key-1", 1));
        assertFalse(cap.check("key-2", 1));

        clock.advance(Duration.ofHours(24));

        assertTrue(cap.check("key-3", 1));
    }

    @Test
    void theKeyCounterResetsAfter24Hours() {
        MutableClock clock = new MutableClock(START);
        AnonymousDailyCap cap = new AnonymousDailyCap(clock, 1_000, 1);

        assertTrue(cap.check("key-1", 1));
        assertFalse(cap.check("key-1", 1));

        clock.advance(Duration.ofHours(24));

        assertTrue(cap.check("key-1", 1));
    }

    @Test
    void theCounterStaysAliveOneMillisecondBeforeTheWindowEnds() {
        MutableClock clock = new MutableClock(START);
        AnonymousDailyCap cap = new AnonymousDailyCap(clock, 1_000, 1);

        assertTrue(cap.check("key-1", 1));

        clock.advance(Duration.ofHours(24).minusMillis(1));

        assertFalse(cap.check("key-1", 1));
    }

    @Test
    void theKeyMapEvictsTheLeastRecentKeyAt20001KeysAndHolds20000() {
        MutableClock clock = new MutableClock(START);
        AnonymousDailyCap cap = new AnonymousDailyCap(clock, Long.MAX_VALUE, 1);

        for (int i = 0; i < AnonymousDailyCap.MAX_KEYS; i++) {
            assertTrue(cap.check("key-" + i, 1), "key-" + i + " must pass.");
        }
        // key-0 is now the oldest key. One more distinct key evicts it.
        assertTrue(cap.check("one-key-too-many", 1));

        // key-0 lost its counter through the eviction, so it starts a
        // fresh count, and one more entry of it still passes.
        assertTrue(cap.check("key-0", 1));
    }

    @Test
    void aKeyThatStaysUnderItsCapIsNeverEvictedEarly() {
        MutableClock clock = new MutableClock(START);
        AnonymousDailyCap cap = new AnonymousDailyCap(clock, Long.MAX_VALUE, 2);

        assertTrue(cap.check("key-0", 1));
        for (int i = 1; i < AnonymousDailyCap.MAX_KEYS; i++) {
            cap.check("key-" + i, 1);
        }
        // key-0 already used its map slot; a lookup moves it to the
        // newest end, so the next new key evicts key-1, not key-0.
        cap.check("key-0", 1);
        cap.check("one-more-key", 1);

        // key-0 still holds its own two-count window; one more entry
        // goes above its cap of 2 and drops.
        assertFalse(cap.check("key-0", 1));
    }

    @Test
    void aNonPositiveMaxGlobalEventsPerDayThrows() {
        MutableClock clock = new MutableClock(START);
        assertThrows(IllegalArgumentException.class, () -> new AnonymousDailyCap(clock, 0, 1));
    }

    @Test
    void aNonPositiveMaxEventsPerKeyPerDayThrows() {
        MutableClock clock = new MutableClock(START);
        assertThrows(IllegalArgumentException.class, () -> new AnonymousDailyCap(clock, 1, 0));
    }

    @Test
    void anEntryCountBelowOneThrows() {
        MutableClock clock = new MutableClock(START);
        AnonymousDailyCap cap = new AnonymousDailyCap(clock, 10, 10);
        assertThrows(IllegalArgumentException.class, () -> cap.check("key-1", 0));
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
