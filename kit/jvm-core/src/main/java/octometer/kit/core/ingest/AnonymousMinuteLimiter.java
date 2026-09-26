package octometer.kit.core.ingest;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The anonymous per-minute limiter of design decision D43 and issue #116.
 * It applies three counters to one key, in one fixed window of 60
 * seconds: the request count, the click entry count, and the
 * `octo:session-start` entry count. It runs only when the app records an
 * anonymous click, for a request with no user id. Above one counter the
 * ingest route answers 429 (contract rule C19). These three counters
 * replace the client-address limit of design decision D20 for such a
 * request.
 *
 * <p><strong>The window form.</strong> The three counters of one key
 * share one window, from the first counted request or entry of that key.
 * The window is not aligned to the clock, the form of
 * {@link AnonymousDailyCap}. A counter resets at the first check after
 * its window ends, also when the clock steps backwards (for example
 * after an NTP correction).
 *
 * <p><strong>The key map.</strong> It holds a maximum of
 * {@value #MAX_KEYS} keys, with an LRU eviction, the form of
 * {@link IngestRateLimiter} and {@link AnonymousDailyCap}. A key that
 * leaves the map through the eviction starts a fresh window at its next
 * check.
 *
 * <p><strong>The warning.</strong> This class writes a maximum of one
 * WARN line for each elapsed 60-second window since the last one. The
 * line names no key and no address. This class holds the log call
 * outside its lock, so a slow log write never stalls a check of a
 * different key.
 *
 * <p>This class is thread-safe. One lock guards the key map, so a check
 * of the three counters of one key, and the count of a passed request or
 * entry, run as one atomic step.
 */
public final class AnonymousMinuteLimiter {

    /** The cap of the key map (design decision D43, the form of {@link AnonymousDailyCap}). */
    static final int MAX_KEYS = 20_000;

    private static final long WINDOW_MILLIS = Duration.ofSeconds(60).toMillis();
    private static final long WARN_THROTTLE_MILLIS = WINDOW_MILLIS;

    private static final Logger LOGGER = System.getLogger("octometer.kit.core");

    private final Clock clock;
    private final long requestLimit;
    private final long clickEntryLimit;
    private final long sessionStartLimit;

    private final Object lock = new Object();
    private final LinkedHashMap<String, Counters> keyCounters;

    private long lastWarnedAtMillis = Long.MIN_VALUE;

    /**
     * Builds one per-minute limiter.
     *
     * @param clock the clock of each window. A test gives a fixed or a
     *   mutable clock; an app gives the system clock.
     * @param requestLimit the limit of {@code OCTOMETER_ANON_REQ_PER_MIN}.
     *   It must be 1 or more.
     * @param clickEntryLimit the limit of
     *   {@code OCTOMETER_ANON_EVENTS_PER_MIN}. It must be 1 or more.
     * @param sessionStartLimit the limit of
     *   {@code OCTOMETER_ANON_SESSIONS_PER_MIN}. It must be 1 or more.
     */
    public AnonymousMinuteLimiter(Clock clock, long requestLimit, long clickEntryLimit, long sessionStartLimit) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (requestLimit < 1) {
            throw new IllegalArgumentException("requestLimit must be 1 or more");
        }
        if (clickEntryLimit < 1) {
            throw new IllegalArgumentException("clickEntryLimit must be 1 or more");
        }
        if (sessionStartLimit < 1) {
            throw new IllegalArgumentException("sessionStartLimit must be 1 or more");
        }
        this.requestLimit = requestLimit;
        this.clickEntryLimit = clickEntryLimit;
        this.sessionStartLimit = sessionStartLimit;
        this.keyCounters = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Counters> eldest) {
                return size() > MAX_KEYS;
            }
        };
    }

    /**
     * Checks the request counter of {@code key}, then adds one request
     * to it. The ingest route calls this method before it reads the
     * request body, beside the rate limiter of design decision D20,
     * because this counter needs no parsed entry.
     *
     * @return {@code true} when the request passes; {@code false} when
     *   it goes above the request limit.
     */
    public boolean checkRequest(String key) {
        return check(key, 1, 0, 0);
    }

    /**
     * Checks the click entry counter and the session-start entry
     * counter of {@code key}, then adds {@code clickEntryCount} and
     * {@code sessionStartEntryCount} to them. The ingest route calls
     * this method after it parses the request body, because only the
     * parsed batch tells apart a click entry from an
     * `octo:session-start` entry.
     *
     * @param clickEntryCount the count of entries of the batch whose
     *   element is not `octo:session-start`. It must be 0 or more.
     * @param sessionStartEntryCount the count of entries of the batch
     *   whose element is `octo:session-start`. It must be 0 or more.
     * @return {@code true} when the batch passes; {@code false} when it
     *   goes above the click entry limit or the session-start limit.
     */
    public boolean checkEntries(String key, int clickEntryCount, int sessionStartEntryCount) {
        if (clickEntryCount < 0) {
            throw new IllegalArgumentException("clickEntryCount must not be negative");
        }
        if (sessionStartEntryCount < 0) {
            throw new IllegalArgumentException("sessionStartEntryCount must not be negative");
        }
        return check(key, 0, clickEntryCount, sessionStartEntryCount);
    }

    private boolean check(String key, int requestDelta, int clickEntryDelta, int sessionStartDelta) {
        Objects.requireNonNull(key, "key must not be null");
        long now = clock.millis();
        boolean allowed;
        boolean dueForWarning;
        synchronized (lock) {
            Counters counters = keyCounters.get(key);
            if (counters == null) {
                counters = new Counters();
                keyCounters.put(key, counters);
            }
            counters.resetIfWindowEnded(now);
            counters.startWindowIfNeeded(now);
            counters.requestCount += requestDelta;
            counters.clickEntryCount += clickEntryDelta;
            counters.sessionStartCount += sessionStartDelta;

            // Each of the three counters gates this call only when this
            // call itself adds to that counter. A batch with zero click
            // entries must never fail because a different key check, on
            // an earlier call, already pushed the click counter of this
            // key above its own limit; the three counters stay
            // independent this way, the point of having three of them
            // instead of one combined counter.
            boolean overRequestLimit = requestDelta > 0 && counters.requestCount > requestLimit;
            boolean overClickEntryLimit = clickEntryDelta > 0 && counters.clickEntryCount > clickEntryLimit;
            boolean overSessionStartLimit = sessionStartDelta > 0 && counters.sessionStartCount > sessionStartLimit;
            allowed = !overRequestLimit && !overClickEntryLimit && !overSessionStartLimit;
            dueForWarning = !allowed && dueForWarning(now);
        }
        // The log call runs after the lock ends (the form of
        // AnonymousDailyCap). A slow log write then never stalls a check
        // of a different key.
        if (dueForWarning) {
            logWarning();
        }
        return allowed;
    }

    /**
     * Returns the key count of the key map. A test uses this method to
     * confirm the map size after an eviction. The class itself needs no
     * such count.
     */
    int keyCount() {
        synchronized (lock) {
            return keyCounters.size();
        }
    }

    /**
     * True at most one time for each elapsed 60-second window since the
     * last true result, measured from {@code now}. This method also
     * records {@code now} as the last warned instant. It must run
     * inside {@link #lock}. Only the log write itself may run outside
     * it.
     */
    private boolean dueForWarning(long now) {
        if (lastWarnedAtMillis != Long.MIN_VALUE && now - lastWarnedAtMillis < WARN_THROTTLE_MILLIS) {
            return false;
        }
        lastWarnedAtMillis = now;
        return true;
    }

    /**
     * Writes one warning. The message never holds a key or an address
     * (design decision D15).
     */
    private void logWarning() {
        LOGGER.log(Level.WARNING, "The anonymous minute limiter rejects one or more requests in "
                + "this 60-second window (design decision D43). The log holds no key and no address.");
    }

    /**
     * The three counters of one key, and their shared window start
     * instant. A test never builds this class directly; {@link #check}
     * manages each instance.
     */
    private static final class Counters {
        private long windowStart = Long.MIN_VALUE;
        private long requestCount;
        private long clickEntryCount;
        private long sessionStartCount;

        /**
         * Resets the three counters to zero, with no window, when
         * {@code now} is at or after the end of the live window. It
         * also resets when {@code now} sits before the start of the
         * live window, the guard for a clock that steps backwards (the
         * form of the counter of {@link AnonymousDailyCap}). These
         * counters never reset while {@code now} still sits inside
         * their window.
         */
        void resetIfWindowEnded(long now) {
            if (windowStart != Long.MIN_VALUE && (now >= windowStart + WINDOW_MILLIS || now < windowStart)) {
                windowStart = Long.MIN_VALUE;
                requestCount = 0;
                clickEntryCount = 0;
                sessionStartCount = 0;
            }
        }

        /**
         * Starts a new window at {@code now}, when these counters hold
         * no window yet (design decision D43: the window starts at the
         * first counted request or entry).
         */
        void startWindowIfNeeded(long now) {
            if (windowStart == Long.MIN_VALUE) {
                windowStart = now;
            }
        }
    }
}
