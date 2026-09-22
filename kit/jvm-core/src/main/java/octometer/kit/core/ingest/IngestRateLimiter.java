package octometer.kit.core.ingest;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The in-memory ingest rate limiter of design decision D20 and issue #33.
 * One key holds one counter in a fixed 60-second window. The window is
 * fixed, not a sliding window: each key starts a new count of zero at the
 * start of each 60-second slot of the clock, and it never looks back at
 * an older request.
 *
 * <p>A user id gives the key {@code "user:" + userId}, with a limit of
 * {@value #USER_LIMIT_PER_WINDOW} requests. A {@code null} user id gives
 * the key {@code "ip:" + clientAddress}, with a limit of
 * {@value #CLIENT_ADDRESS_LIMIT_PER_WINDOW} requests. The two kinds of
 * key never collide, also when the raw text is the same.
 *
 * <p><strong>The map of this class.</strong> This class holds each key of
 * the current window in one map. The whole map clears at the start of
 * each window, so the acceptance criteria of issue #33 that name "the
 * window" also bound the size of the map. The map holds a maximum of
 * {@value #MAX_TRACKED_KEYS} distinct keys at one time (a limit on the
 * memory of this class: {@value #MAX_TRACKED_KEYS} keys of about 60
 * characters each, plus one small counter each, stay near 2 MB). Once the
 * map is full, a key that the map has not tracked yet in this window gets
 * {@link RateLimitResult#LIMITED} until the window ends; the map never
 * evicts an existing key to make room for a new one. Issue #118 replaces
 * this one map with two separate maps, each with its own LRU eviction
 * (design decision D20, the Related section of issue #33).
 *
 * <p><strong>Cost of a rejection.</strong> A rejected request allocates
 * no object beyond one short key text; {@link RateLimitResult#LIMITED} is
 * a fixed enum constant, never a new exception. A rejection writes no log
 * line of its own. This class writes a maximum of one warning for the
 * whole window, and that one line never holds a key, a user id, or a
 * client address (design decision D15).
 *
 * <p>This class is thread-safe. Many requests can call {@link #check} at
 * the same time.
 */
public final class IngestRateLimiter {

    /** The limit of one user id key in one window (design decision D20). */
    static final int USER_LIMIT_PER_WINDOW = 30;

    /** The limit of one client address key in one window (design decision D20). */
    static final int CLIENT_ADDRESS_LIMIT_PER_WINDOW = 120;

    /**
     * The size cap of the key map (issue #33, acceptance criterion "With
     * 10 000 keys in the map"). This value also bounds the memory of one
     * {@link IngestRateLimiter} instance; see the class comment.
     */
    static final int MAX_TRACKED_KEYS = 10_000;

    private static final long WINDOW_MILLIS = Duration.ofSeconds(60).toMillis();

    private static final Logger LOGGER = System.getLogger("octometer.kit.core");

    private final Clock clock;

    /** Guards a change of {@link #counters} and of {@link #currentWindowStart} together. */
    private final Object windowLock = new Object();

    private volatile ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();
    private volatile long currentWindowStart = Long.MIN_VALUE;
    private final AtomicLong lastWarnedWindowStart = new AtomicLong(Long.MIN_VALUE);

    public IngestRateLimiter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Checks one request against the window limit of design decision D20.
     *
     * @param userId the user id of the request, or {@code null} for a
     *   request with no signed-in user (contract rule C6).
     * @param clientAddress the client address of the request (issue #33,
     *   step 3). This method reads it only when {@code userId} is
     *   {@code null}, but the parameter itself must never be
     *   {@code null}.
     * @throws NullPointerException when {@code userId} is {@code null}
     *   and {@code clientAddress} is also {@code null}.
     */
    public RateLimitResult check(String userId, String clientAddress) {
        boolean anonymous = userId == null;
        String key;
        int limit;
        if (anonymous) {
            Objects.requireNonNull(clientAddress, "clientAddress must not be null when userId is null");
            key = "ip:" + clientAddress;
            limit = CLIENT_ADDRESS_LIMIT_PER_WINDOW;
        } else {
            key = "user:" + userId;
            limit = USER_LIMIT_PER_WINDOW;
        }

        long now = clock.millis();
        long windowStart = now - Math.floorMod(now, WINDOW_MILLIS);
        ConcurrentHashMap<String, AtomicInteger> currentCounters = counters(windowStart);

        AtomicInteger counter = currentCounters.get(key);
        if (counter == null) {
            // A soft cap: two threads can each pass this check at the
            // same instant and both add one key, so the map can hold a
            // small number of keys above MAX_TRACKED_KEYS for a moment.
            // The cap still bounds the memory of this class, because
            // the map clears in full at the next window.
            if (currentCounters.size() >= MAX_TRACKED_KEYS) {
                warnOncePerWindow(windowStart);
                return RateLimitResult.LIMITED;
            }
            counter = currentCounters.computeIfAbsent(key, ignoredKey -> new AtomicInteger());
        }

        int count = counter.incrementAndGet();
        if (count > limit) {
            warnOncePerWindow(windowStart);
            return RateLimitResult.LIMITED;
        }
        return RateLimitResult.ALLOWED;
    }

    /**
     * Returns the key map of {@code windowStart}. It clears the map once,
     * the first time a caller reaches a new window; every other caller of
     * the same window reads the same map.
     */
    private ConcurrentHashMap<String, AtomicInteger> counters(long windowStart) {
        if (currentWindowStart == windowStart) {
            return counters;
        }
        synchronized (windowLock) {
            if (currentWindowStart != windowStart) {
                counters = new ConcurrentHashMap<>();
                currentWindowStart = windowStart;
            }
            return counters;
        }
    }

    /**
     * Writes one warning for {@code windowStart}, at most one time. The
     * message names no key, no user id, and no client address (design
     * decision D15).
     */
    private void warnOncePerWindow(long windowStart) {
        long previouslyWarnedWindowStart = lastWarnedWindowStart.get();
        if (previouslyWarnedWindowStart == windowStart) {
            return;
        }
        if (lastWarnedWindowStart.compareAndSet(previouslyWarnedWindowStart, windowStart)) {
            LOGGER.log(Level.WARNING, "The ingest rate limiter rejects one or more requests in "
                    + "this 60-second window (design decision D20). The log holds no key, no "
                    + "user id, and no client address.");
        }
    }
}
