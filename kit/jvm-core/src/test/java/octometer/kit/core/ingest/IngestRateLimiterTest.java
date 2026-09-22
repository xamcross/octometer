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
    void aClientAddressKeyAndAUserIdKeyOfTheSameTextStayIndependent() {
        // Security review BLOCKER 2 of pull request #158: the earlier
        // order of this test could not fail. The client address key
        // reaches its limit first here, so a shared counter (the bug)
        // would carry that count into the user id check below and give
        // LIMITED, not ALLOWED.
        MutableClock clock = new MutableClock(START);
        IngestRateLimiter limiter = new IngestRateLimiter(clock);
        String sharedText = "203.0.113.9";

        for (int i = 1; i <= 30; i++) {
            assertEquals(RateLimitResult.ALLOWED, limiter.check(null, sharedText));
        }

        // The client address key already used 30 of its 120 requests. A
        // user id key of the same text is an independent counter (two
        // separate maps, design decision D20), so this first user
        // request still passes.
        assertEquals(RateLimitResult.ALLOWED, limiter.check(sharedText, "10.0.0.1"));
    }

    @Test
    void aNullClientAddressWithNoUserIdThrowsNullPointerException() {
        MutableClock clock = new MutableClock(START);
        IngestRateLimiter limiter = new IngestRateLimiter(clock);

        assertThrows(NullPointerException.class, () -> limiter.check(null, null));
    }

    @Test
    void tenThousandAnonymousKeysBlockNoUserAndNoNewVisitor() {
        // Security review MAJOR 2 and concurrency review MAJOR 4 of pull
        // request #158: the earlier one-map design let 10 000 cheap,
        // distinct anonymous keys fill the whole map and give LIMITED to
        // every other visitor, signed in or not. The two maps of design
        // decision D20 give the anonymous flood its own map (a cap of
        // 20 000), so it never touches the user map.
        MutableClock clock = new MutableClock(START);
        IngestRateLimiter limiter = new IngestRateLimiter(clock);

        // A signed-in user already used its full window budget, before
        // an anonymous flood arrives.
        for (int i = 1; i <= 30; i++) {
            assertEquals(RateLimitResult.ALLOWED, limiter.check("real-user-1", "198.51.100.5"));
        }
        assertEquals(RateLimitResult.LIMITED, limiter.check("real-user-1", "198.51.100.5"));

        for (int i = 0; i < 10_000; i++) {
            assertEquals(RateLimitResult.ALLOWED, limiter.check(null, "anon-key-" + i),
                    "Anonymous key " + i + " must pass.");
        }

        // The flood must never evict a key of the user map, because the
        // user map and the client address map are two separate objects
        // (design decision D20). real-user-1 thus stays limited, and a
        // second signed-in user, and a new anonymous visitor, both start
        // a fresh counter of their own.
        assertEquals(RateLimitResult.LIMITED, limiter.check("real-user-1", "198.51.100.5"),
                "A signed-in user's own limit must survive an anonymous flood.");
        assertEquals(RateLimitResult.ALLOWED, limiter.check("real-user-2", "198.51.100.6"),
                "A second signed-in user must stay free of an anonymous flood.");
        assertEquals(RateLimitResult.ALLOWED, limiter.check(null, "a-brand-new-visitor"),
                "A new anonymous visitor must stay free of an anonymous flood.");
    }

    @Test
    void aFullClientAddressMapEvictsItsOldestKeyAndGivesTheNewKeyAFreshCounter() {
        MutableClock clock = new MutableClock(START);
        IngestRateLimiter limiter = new IngestRateLimiter(clock);

        for (int i = 0; i < IngestRateLimiter.MAX_CLIENT_ADDRESS_KEYS; i++) {
            assertEquals(RateLimitResult.ALLOWED, limiter.check(null, "key-" + i));
        }
        // key-0 is now the oldest key of the map (design decision D20).
        // One more distinct key evicts it, and the new key still gets
        // the rate-limit result of a fresh counter, never LIMITED for
        // the reason that the map was full.
        assertEquals(RateLimitResult.ALLOWED, limiter.check(null, "one-key-too-many"));

        // key-0 lost its counter through the eviction, so it now starts
        // a fresh count of its own, not the count of the first loop.
        for (int i = 1; i <= IngestRateLimiter.CLIENT_ADDRESS_LIMIT_PER_WINDOW; i++) {
            assertEquals(RateLimitResult.ALLOWED, limiter.check(null, "key-0"),
                    "Request " + i + " of key-0 after its eviction must pass.");
        }
        assertEquals(RateLimitResult.LIMITED, limiter.check(null, "key-0"));
    }

    @Test
    void aKeyTextAbove64CharactersIsShortenedAndTwoLongKeysNeverJoin() {
        // Security review MAJOR 3 and concurrency review BLOCKER 2 of
        // pull request #158: a client address or a user id with no
        // length limit could fill one map with a few huge keys.
        MutableClock clock = new MutableClock(START);
        IngestRateLimiter limiter = new IngestRateLimiter(clock);
        // The two values share the same first 64 characters and differ
        // only after that point, so a shortenKey that truncated instead
        // of hashing would join them into one key. This is the exact
        // case that "two keys never join" must rule out.
        String sharedPrefix = "p".repeat(64);
        String longValueA = sharedPrefix + "a".repeat(5_936);
        String longValueB = sharedPrefix + "b".repeat(5_936);

        for (int i = 1; i <= IngestRateLimiter.CLIENT_ADDRESS_LIMIT_PER_WINDOW; i++) {
            assertEquals(RateLimitResult.ALLOWED, limiter.check(null, longValueA),
                    "Request " + i + " of the first long value must pass.");
        }
        assertEquals(RateLimitResult.LIMITED, limiter.check(null, longValueA));

        // A different 6 000-character value has a different SHA-256 hex
        // text, so it is a different key: it starts its own fresh count,
        // and the map never holds a 6 000-character key text.
        assertEquals(RateLimitResult.ALLOWED, limiter.check(null, longValueB));
    }

    @Test
    void oneThousandRequestsFrom8ThreadsAgainstOneKeyGiveExactlyTheLimitOfAcceptedRequests() throws InterruptedException {
        int threadCount = 8;
        int requestCount = 1000;
        int runCount = 20;
        for (int run = 1; run <= runCount; run++) {
            MutableClock clock = new MutableClock(START);
            IngestRateLimiter limiter = new IngestRateLimiter(clock);
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

            assertEquals(IngestRateLimiter.USER_LIMIT_PER_WINDOW, allowedCount.get(), "Run " + run);
            assertEquals(requestCount - IngestRateLimiter.USER_LIMIT_PER_WINDOW, limitedCount.get(), "Run " + run);
        }
    }

    @Test
    void aStragglerThreadAtAWindowBoundaryNeverResetsTheLiveWindow() throws InterruptedException {
        // Concurrency review BLOCKER 1 of pull request #158: a thread
        // that reads the clock just before a boundary, then runs after
        // other threads already moved the window, could reset the whole
        // window and give every key a fresh quota inside the same
        // minute. This test reproduces the reviewer's own latch-clock
        // scenario, five times.
        for (int run = 1; run <= 5; run++) {
            Instant windowOneStart = START;
            Instant windowTwoStart = START.plus(Duration.ofSeconds(60));
            CountDownLatch releaseStraggler = new CountDownLatch(1);
            LatchClock clock = new LatchClock(windowOneStart, windowTwoStart, releaseStraggler);
            IngestRateLimiter limiter = new IngestRateLimiter(clock);

            int allowedInWindowTwo = 0;
            for (int i = 1; i <= 30; i++) {
                if (limiter.check("user-1", "203.0.113.9") == RateLimitResult.ALLOWED) {
                    allowedInWindowTwo++;
                }
            }
            RateLimitResult request31 = limiter.check("user-1", "203.0.113.9");

            // The straggler thread reads the clock as window one (a
            // stale read, from before the boundary), then calls check()
            // after window two already exists and already holds the
            // count above. A monotonic guard must keep the straggler
            // inside window two, never roll the window back to a fresh,
            // empty map.
            Thread straggler = new Thread(
                    () -> limiter.check("user-1", "203.0.113.9"), "straggler");
            straggler.start();
            releaseStraggler.countDown();
            straggler.join(5_000);
            assertFalse(straggler.isAlive(), "Run " + run + ": the straggler thread must finish.");

            int extraAllowed = 0;
            for (int i = 0; i < 40; i++) {
                if (limiter.check("user-1", "203.0.113.9") == RateLimitResult.ALLOWED) {
                    extraAllowed++;
                }
            }

            assertEquals(30, allowedInWindowTwo, "Run " + run);
            assertEquals(RateLimitResult.LIMITED, request31, "Run " + run);
            assertEquals(0, extraAllowed, "Run " + run + ": the straggler must not reset the window.");
        }
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

    /**
     * The latch-clock test double of the concurrency review of pull
     * request #158. It gives the thread named {@code "straggler"} the
     * instant of the old window, and every other thread the instant of
     * the new window. The straggler thread waits inside {@link #instant()}
     * for {@code released}, so a test controls the exact interleave: the
     * straggler reads its stale instant only after the main thread
     * already moved the window and drove it to the limit.
     */
    private static final class LatchClock extends Clock {
        private final Instant oldWindowInstant;
        private final Instant newWindowInstant;
        private final CountDownLatch released;

        LatchClock(Instant oldWindowInstant, Instant newWindowInstant, CountDownLatch released) {
            this.oldWindowInstant = oldWindowInstant;
            this.newWindowInstant = newWindowInstant;
            this.released = released;
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
            if (!"straggler".equals(Thread.currentThread().getName())) {
                return newWindowInstant;
            }
            try {
                assertTrue(released.await(5, TimeUnit.SECONDS), "The main thread must release the straggler.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return oldWindowInstant;
        }
    }
}
