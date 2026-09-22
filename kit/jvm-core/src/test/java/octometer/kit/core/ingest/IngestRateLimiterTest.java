package octometer.kit.core.ingest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of {@link IngestRateLimiter} against design decision D20 and the
 * acceptance criteria of issue #33.
 */
class IngestRateLimiterTest {

    private static final Instant START = Instant.parse("2026-09-22T10:00:00.000Z");

    @Test
    void request31OfOneUserIdInTheSameWindowIsLimited() {
        MutableClock clock = new MutableClock(START);
        IngestRateLimiter limiter = new IngestRateLimiter(clock);

        for (int i = 1; i <= 30; i++) {
            assertEquals(RateLimitResult.ALLOWED, limiter.check("user-1", "203.0.113.9"),
                    "Request " + i + " of 30 must pass.");
        }
        assertEquals(RateLimitResult.LIMITED, limiter.check("user-1", "203.0.113.9"));
    }

    @Test
    void aRequestOfTheSameUserIdAfterTheWindowEndsPasses() {
        MutableClock clock = new MutableClock(START);
        IngestRateLimiter limiter = new IngestRateLimiter(clock);

        for (int i = 1; i <= 31; i++) {
            limiter.check("user-1", "203.0.113.9");
        }
        assertEquals(RateLimitResult.LIMITED, limiter.check("user-1", "203.0.113.9"));

        clock.advance(Duration.ofSeconds(60));

        assertEquals(RateLimitResult.ALLOWED, limiter.check("user-1", "203.0.113.9"));
    }

    @Test
    void withoutAUserIdRequest121OfOneClientAddressIsLimited() {
        MutableClock clock = new MutableClock(START);
        IngestRateLimiter limiter = new IngestRateLimiter(clock);

        for (int i = 1; i <= 120; i++) {
            assertEquals(RateLimitResult.ALLOWED, limiter.check(null, "203.0.113.9"),
                    "Request " + i + " of 120 must pass.");
        }
        assertEquals(RateLimitResult.LIMITED, limiter.check(null, "203.0.113.9"));
    }

    @Test
    void aClientAddressRequestAfterTheWindowEndsPasses() {
        MutableClock clock = new MutableClock(START);
        IngestRateLimiter limiter = new IngestRateLimiter(clock);

        for (int i = 1; i <= 121; i++) {
            limiter.check(null, "203.0.113.9");
        }
        assertEquals(RateLimitResult.LIMITED, limiter.check(null, "203.0.113.9"));

        clock.advance(Duration.ofSeconds(60));

        assertEquals(RateLimitResult.ALLOWED, limiter.check(null, "203.0.113.9"));
    }

    @Test
    void aUserIdKeyAndAClientAddressKeyOfTheSameTextStayIndependent() {
        MutableClock clock = new MutableClock(START);
        IngestRateLimiter limiter = new IngestRateLimiter(clock);
        String sharedText = "203.0.113.9";

        for (int i = 1; i <= 30; i++) {
            assertEquals(RateLimitResult.ALLOWED, limiter.check(sharedText, "10.0.0.1"));
        }
        assertEquals(RateLimitResult.LIMITED, limiter.check(sharedText, "10.0.0.1"));

        // The anonymous key uses the same text as the user id above. A
        // shared counter would already be over the user limit of 30; an
        // independent counter still has all 120 anonymous requests free.
        assertEquals(RateLimitResult.ALLOWED, limiter.check(null, sharedText));
    }

    @Test
    void aNullClientAddressWithNoUserIdThrowsNullPointerException() {
        MutableClock clock = new MutableClock(START);
        IngestRateLimiter limiter = new IngestRateLimiter(clock);

        assertThrows(NullPointerException.class, () -> limiter.check(null, null));
    }

    @Test
    void whenTheMapHolds10000KeysANewKeyIsLimitedUntilTheWindowEnds() {
        MutableClock clock = new MutableClock(START);
        IngestRateLimiter limiter = new IngestRateLimiter(clock);

        for (int i = 0; i < IngestRateLimiter.MAX_TRACKED_KEYS; i++) {
            assertEquals(RateLimitResult.ALLOWED, limiter.check(null, "key-" + i));
        }

        // The map already tracks MAX_TRACKED_KEYS distinct keys. A key
        // that the map has not tracked yet in this window is limited,
        // so the map never grows without a bound (a limit on the
        // memory, issue #33).
        assertEquals(RateLimitResult.LIMITED, limiter.check(null, "a-brand-new-key"));
        // An existing key still gets its own count checked.
        assertEquals(RateLimitResult.ALLOWED, limiter.check(null, "key-0"));

        clock.advance(Duration.ofSeconds(60));

        // The map is empty at the start of each window (design decision
        // D20), so the new key now passes.
        assertEquals(RateLimitResult.ALLOWED, limiter.check(null, "a-brand-new-key"));
    }

    @Test
    void oneThousandRequestsFrom8ThreadsAgainstOneKeyGiveExactlyTheLimitOfAcceptedRequests() throws InterruptedException {
        MutableClock clock = new MutableClock(START);
        IngestRateLimiter limiter = new IngestRateLimiter(clock);
        int threadCount = 8;
        int requestCount = 1000;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowedCount = new AtomicInteger();
        AtomicInteger limitedCount = new AtomicInteger();
        try {
            for (int t = 0; t < threadCount; t++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < requestCount / threadCount; i++) {
                        RateLimitResult result = limiter.check("shared-user", "203.0.113.9");
                        if (result == RateLimitResult.ALLOWED) {
                            allowedCount.incrementAndGet();
                        } else {
                            limitedCount.incrementAndGet();
                        }
                    }
                });
            }
            ready.await();
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(IngestRateLimiter.USER_LIMIT_PER_WINDOW, allowedCount.get());
        assertEquals(requestCount - IngestRateLimiter.USER_LIMIT_PER_WINDOW, limitedCount.get());
    }

    @Test
    void aRejectionWritesAMaximumOfOneLogLineForTheWindowWithNoKeyInIt() {
        CapturingLoggerFinder.clear();
        MutableClock clock = new MutableClock(START);
        IngestRateLimiter limiter = new IngestRateLimiter(clock);

        for (int i = 0; i < 500; i++) {
            limiter.check(null, "198.51.100.7");
        }

        assertEquals(1, CapturingLoggerFinder.messages().size());
        String message = CapturingLoggerFinder.messages().peek();
        assertFalse(message.contains("198.51.100.7"));
    }

    @Test
    void aRejectionInEachOfTwoDifferentWindowsWritesTwoLogLinesInTotal() {
        CapturingLoggerFinder.clear();
        MutableClock clock = new MutableClock(START);
        IngestRateLimiter limiter = new IngestRateLimiter(clock);

        for (int i = 0; i < 125; i++) {
            limiter.check(null, "198.51.100.7");
        }
        clock.advance(Duration.ofSeconds(60));
        for (int i = 0; i < 125; i++) {
            limiter.check(null, "198.51.100.7");
        }

        assertEquals(2, CapturingLoggerFinder.messages().size());
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
