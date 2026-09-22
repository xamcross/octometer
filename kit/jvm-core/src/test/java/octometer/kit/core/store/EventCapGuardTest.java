package octometer.kit.core.store;

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
import octometer.kit.core.ingest.CapturingLoggerFinder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of {@link EventCapGuard} against design decision D21 and issue #34.
 * A test never queries a real database; it gives a fake
 * {@link EventCountEstimator} instead, so this module keeps no dependency
 * on a store.
 */
class EventCapGuardTest {

    private static final Instant START = Instant.parse("2026-09-22T10:00:00.000Z");

    @Test
    void anEstimateBelowTheCapIsNotOverCap() {
        CountingEstimator estimator = new CountingEstimator(199_999);
        EventCapGuard guard = new EventCapGuard(200_000, Duration.ofSeconds(60), new MutableClock(START), estimator);

        assertFalse(guard.isOverCap());
    }

    @Test
    void anEstimateAtTheCapIsOverCap() {
        CountingEstimator estimator = new CountingEstimator(200_000);
        EventCapGuard guard = new EventCapGuard(200_000, Duration.ofSeconds(60), new MutableClock(START), estimator);

        assertTrue(guard.isOverCap());
    }

    @Test
    void anEstimateAboveTheCapIsOverCap() {
        CountingEstimator estimator = new CountingEstimator(250_000);
        EventCapGuard guard = new EventCapGuard(200_000, Duration.ofSeconds(60), new MutableClock(START), estimator);

        assertTrue(guard.isOverCap());
    }

    @Test
    void theGuardCallsTheEstimatorAtMostOneTimeInsideOneRefreshInterval() {
        CountingEstimator estimator = new CountingEstimator(0);
        MutableClock clock = new MutableClock(START);
        EventCapGuard guard = new EventCapGuard(200_000, Duration.ofSeconds(60), clock, estimator);

        for (int i = 0; i < 1000; i++) {
            guard.isOverCap();
        }

        assertEquals(1, estimator.callCount(), "The guard must read the estimate one time only inside 60 seconds.");
    }

    @Test
    void theGuardReadsTheEstimateAgainAfterTheRefreshIntervalPasses() {
        CountingEstimator estimator = new CountingEstimator(0);
        MutableClock clock = new MutableClock(START);
        EventCapGuard guard = new EventCapGuard(200_000, Duration.ofSeconds(60), clock, estimator);
        guard.isOverCap();
        assertEquals(1, estimator.callCount());

        clock.advance(Duration.ofSeconds(60));
        guard.isOverCap();

        assertEquals(2, estimator.callCount());
    }

    @Test
    void aLowerEstimateAfterADeleteLetsTheGuardAcceptEventsAgain() {
        // This test stands in for the TTL delete of the acceptance
        // criteria: the estimator returns a value at the cap, then a
        // lower value once the refresh interval passes.
        CountingEstimator estimator = new CountingEstimator(200_000);
        MutableClock clock = new MutableClock(START);
        EventCapGuard guard = new EventCapGuard(200_000, Duration.ofSeconds(60), clock, estimator);
        assertTrue(guard.isOverCap());

        estimator.setEstimate(100);
        clock.advance(Duration.ofSeconds(60));

        assertFalse(guard.isOverCap());
    }

    @Test
    void oneThousandCallsFrom8ThreadsReadTheEstimateOneTimeOnly() throws InterruptedException {
        int threadCount = 8;
        int callsPerThread = 125;
        CountingEstimator estimator = new CountingEstimator(0);
        MutableClock clock = new MutableClock(START);
        EventCapGuard guard = new EventCapGuard(200_000, Duration.ofSeconds(60), clock, estimator);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
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
                    for (int i = 0; i < callsPerThread; i++) {
                        guard.isOverCap();
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

        assertEquals(1, estimator.callCount());
    }

    @Test
    void anEstimatorThatThrowsFailsOpenAndTheBatchIsNotOverCap() {
        // A database user with too few rights can reject the count query
        // itself (for example an insert-only role). The cap check must
        // never turn that unrelated failure into a full ingest outage:
        // this method must fail open, the same as an estimate under the
        // cap.
        ThrowingEstimator estimator = new ThrowingEstimator();
        EventCapGuard guard = new EventCapGuard(1, Duration.ofSeconds(60), new MutableClock(START), estimator);

        assertFalse(guard.isOverCap());
    }

    @Test
    void anEstimatorThatThrowsIsCalledAtMostOneTimeInsideOneRefreshIntervalAndWarnsOnceEachHour() {
        CapturingLoggerFinder.clear();
        ThrowingEstimator estimator = new ThrowingEstimator();
        MutableClock clock = new MutableClock(START);
        EventCapGuard guard = new EventCapGuard(1, Duration.ofSeconds(60), clock, estimator);

        for (int i = 0; i < 50; i++) {
            guard.isOverCap();
        }

        assertEquals(1, estimator.callCount(), "A failing estimator must still be read at most one time in 60 seconds.");
        assertEquals(1, CapturingLoggerFinder.messages().size(), "The guard must write one warning for the failure.");
    }

    @Test
    void aMaxEventsValueBelowOneThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> new EventCapGuard(0, Duration.ofSeconds(60), new MutableClock(START), new CountingEstimator(0)));
    }

    @Test
    void aZeroRefreshIntervalThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> new EventCapGuard(200_000, Duration.ZERO, new MutableClock(START), new CountingEstimator(0)));
    }

    @Test
    void warnDropOncePerHourWritesOneLogLineForTheHourWithNoUserData() {
        CapturingLoggerFinder.clear();
        MutableClock clock = new MutableClock(START);
        EventCapGuard guard = new EventCapGuard(200_000, Duration.ofSeconds(60), clock, new CountingEstimator(200_000));

        for (int i = 0; i < 50; i++) {
            guard.warnDropOncePerHour();
        }

        assertEquals(1, CapturingLoggerFinder.messages().size());
        String message = CapturingLoggerFinder.messages().peek();
        // The message states the no-user-data policy in words; it must
        // hold no concrete user id, client address, or session id value.
        // Each fake value below stands for a value that a future change
        // must never interpolate into this warning.
        assertFalse(message.contains("user-1"), "The warning must hold no user id value.");
        assertFalse(message.contains("203.0.113"), "The warning must hold no client address value.");
        assertFalse(message.contains("session-"), "The warning must hold no session id value.");
    }

    @Test
    void warnDropOncePerHourWritesASecondLineForTheNextHour() {
        CapturingLoggerFinder.clear();
        MutableClock clock = new MutableClock(START);
        EventCapGuard guard = new EventCapGuard(200_000, Duration.ofSeconds(60), clock, new CountingEstimator(200_000));

        guard.warnDropOncePerHour();
        clock.advance(Duration.ofHours(1));
        guard.warnDropOncePerHour();

        assertEquals(2, CapturingLoggerFinder.messages().size());
    }

    /** An estimator that always throws, as a store with too few rights does. */
    private static final class ThrowingEstimator implements EventCountEstimator {
        private final AtomicInteger calls = new AtomicInteger();

        int callCount() {
            return calls.get();
        }

        @Override
        public long estimatedEventCount() {
            calls.incrementAndGet();
            throw new IllegalStateException("The test estimator always fails.");
        }
    }

    private static final class CountingEstimator implements EventCountEstimator {
        private volatile long estimate;
        private final AtomicInteger calls = new AtomicInteger();

        CountingEstimator(long estimate) {
            this.estimate = estimate;
        }

        void setEstimate(long estimate) {
            this.estimate = estimate;
        }

        int callCount() {
            return calls.get();
        }

        @Override
        public long estimatedEventCount() {
            calls.incrementAndGet();
            return estimate;
        }
    }

    /** A {@link Clock} that a test can move forward. */
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
