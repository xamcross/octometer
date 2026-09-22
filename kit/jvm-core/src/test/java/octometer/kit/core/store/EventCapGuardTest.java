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
import java.util.concurrent.atomic.AtomicLong;
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

    @Test
    void theHourThrottleUsesElapsedTimeNotTheUtcClockHour() {
        // MINOR of both reviews of pull request #165: two warnings just
        // across a UTC clock-hour boundary must still count as one.
        CapturingLoggerFinder.clear();
        Instant justBeforeTheHour = Instant.parse("2026-09-22T10:59:59.000Z");
        MutableClock clock = new MutableClock(justBeforeTheHour);
        EventCapGuard guard = new EventCapGuard(200_000, Duration.ofSeconds(60), clock, new CountingEstimator(200_000));

        guard.warnDropOncePerHour();
        clock.advance(Duration.ofSeconds(2));
        guard.warnDropOncePerHour();

        assertEquals(1, CapturingLoggerFinder.messages().size(),
                "Two warnings 2 seconds apart must count as one, even across a UTC clock-hour boundary.");
    }

    // The three states of BLOCKER 1 of the security review of pull
    // request #165: a normal read, a count error that keeps the last
    // good estimate, and a fail-closed state after 3 errors in a row.

    @Test
    void aCountErrorKeepsTheLastGoodEstimateInsteadOfResettingItToZero() {
        FlakyEstimator estimator = new FlakyEstimator(200);
        MutableClock clock = new MutableClock(START);
        EventCapGuard guard = new EventCapGuard(200, Duration.ofSeconds(60), clock, estimator);
        assertTrue(guard.isOverCap(), "State 1: a normal read at the cap is over cap.");

        estimator.startFailing();
        clock.advance(Duration.ofSeconds(60));

        assertTrue(guard.isOverCap(), "State 2: a single count error must keep the last known estimate "
                + "(200, at the cap), not reset it to zero.");
    }

    @Test
    void aThirdCountErrorInARowFailsClosedAndASuccessReopensTheGuard() {
        CapturingLoggerFinder.clear();
        FlakyEstimator estimator = new FlakyEstimator(100);
        MutableClock clock = new MutableClock(START);
        EventCapGuard guard = new EventCapGuard(200, Duration.ofSeconds(60), clock, estimator);

        // State 1: a normal read, below the cap.
        assertFalse(guard.isOverCap());

        // State 2: the first and the second count error in a row keep
        // the last good estimate (100), still below the cap.
        estimator.startFailing();
        clock.advance(Duration.ofSeconds(60));
        assertFalse(guard.isOverCap(), "The first count error must keep the last good estimate.");
        clock.advance(Duration.ofSeconds(60));
        assertFalse(guard.isOverCap(), "The second count error must keep the last good estimate.");

        // State 3: the third count error in a row fails closed.
        clock.advance(Duration.ofSeconds(60));
        assertTrue(guard.isOverCap(), "The third count error in a row must fail closed.");
        clock.advance(Duration.ofSeconds(60));
        assertTrue(guard.isOverCap(), "A fourth count error in a row must stay closed.");

        // A later success reopens the guard with the fresh estimate.
        estimator.stopFailing();
        clock.advance(Duration.ofSeconds(60));
        assertFalse(guard.isOverCap(), "A count success must reopen the guard.");

        // The kept-estimate warning and the fail-closed warning use
        // separate hourly throttles, so the fail-closed line always
        // fires even inside the hour of an earlier kept-estimate line:
        // one record for each state, not one for the whole hour.
        assertEquals(2, CapturingLoggerFinder.messages().size(),
                "The guard must warn once for the kept-estimate state and once for the fail-closed state.");
    }

    @Test
    void countAppendedAddsToTheCachedEstimateInsideOneRefreshInterval() {
        // MAJOR 1 of the security review of pull request #165: a flood
        // inside one refresh interval must stop at the cap too.
        CountingEstimator estimator = new CountingEstimator(0);
        MutableClock clock = new MutableClock(START);
        EventCapGuard guard = new EventCapGuard(3, Duration.ofSeconds(60), clock, estimator);

        assertFalse(guard.isOverCap());
        guard.countAppended(1);
        assertFalse(guard.isOverCap());
        guard.countAppended(2);
        assertTrue(guard.isOverCap(), "3 accepted events against a cap of 3 must be over cap.");
    }

    @Test
    void aRefreshResetsTheAppendedCounter() {
        CountingEstimator estimator = new CountingEstimator(0);
        MutableClock clock = new MutableClock(START);
        EventCapGuard guard = new EventCapGuard(1, Duration.ofSeconds(60), clock, estimator);

        guard.isOverCap();
        guard.countAppended(1);
        assertTrue(guard.isOverCap(), "The appended count alone reaches the cap of 1.");

        estimator.setEstimate(0);
        clock.advance(Duration.ofSeconds(60));

        assertFalse(guard.isOverCap(), "The refresh must reset the appended counter to zero.");
    }

    @Test
    void aSecondCallerDuringARefreshReturnsAtOnceWithNoWaitForTheEstimator() throws InterruptedException {
        // MAJOR 2 of the security review of pull request #165: the
        // estimator call must run outside the lock. This test proves it
        // with a strict order: the second call starts only after the
        // estimator call of the first is confirmed in flight (a latch
        // inside the estimator itself), and it must still return in a
        // small fraction of the estimator's 2-second sleep.
        CountDownLatch estimatorStarted = new CountDownLatch(1);
        CountDownLatch releaseEstimator = new CountDownLatch(1);
        GatedEstimator estimator = new GatedEstimator(0, estimatorStarted, releaseEstimator);
        MutableClock clock = new MutableClock(START);
        EventCapGuard guard = new EventCapGuard(200_000, Duration.ofSeconds(60), clock, estimator);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(guard::isOverCap);
            assertTrue(estimatorStarted.await(5, TimeUnit.SECONDS), "The first call must reach the estimator.");

            long beganNanos = System.nanoTime();
            boolean result = pool.submit(guard::isOverCap).get(5, TimeUnit.SECONDS);
            long waitedMillis = (System.nanoTime() - beganNanos) / 1_000_000;

            assertFalse(result, "The second call must read the cached estimate (not over cap).");
            assertTrue(waitedMillis < 500, "The second call must not wait for the estimator; it took "
                    + waitedMillis + " ms, and the estimator sleeps 2000 ms.");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new AssertionError("The second call must return at once.", e);
        } finally {
            releaseEstimator.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }
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

    /**
     * An estimator that gives a fixed estimate, or throws while {@link
     * #startFailing()} is in effect. A test uses it to prove the three
     * states of BLOCKER 1 of the security review of pull request #165.
     */
    private static final class FlakyEstimator implements EventCountEstimator {
        private final long estimate;
        private volatile boolean failing;

        FlakyEstimator(long estimate) {
            this.estimate = estimate;
        }

        void startFailing() {
            failing = true;
        }

        void stopFailing() {
            failing = false;
        }

        @Override
        public long estimatedEventCount() {
            if (failing) {
                throw new IllegalStateException("The test estimator fails while startFailing() is in effect.");
            }
            return estimate;
        }
    }

    /**
     * An estimator that signals {@code started}, then waits for {@code
     * release} before it returns a fixed estimate. A test uses it to
     * order a second call strictly after the estimator call of the
     * first is in flight (MAJOR 2 of the security review of pull
     * request #165). {@code release} still fires quickly in this test,
     * but the estimator also caps its own wait at 2 seconds, so a test
     * failure never hangs the build.
     */
    private static final class GatedEstimator implements EventCountEstimator {
        private final long estimate;
        private final CountDownLatch started;
        private final CountDownLatch release;

        GatedEstimator(long estimate, CountDownLatch started, CountDownLatch release) {
            this.estimate = estimate;
            this.started = started;
            this.release = release;
        }

        @Override
        public long estimatedEventCount() {
            started.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return estimate;
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
