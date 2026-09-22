package octometer.kit.core.ingest;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The daily anonymous cap of design decision D43 and issue #117. One
 * global counter and one counter for each key limit the anonymous
 * events of one day. A batch above either cap drops in full. No part
 * of a dropped batch reaches the store, and a dropped batch changes no
 * counter.
 *
 * <p><strong>The window form.</strong> Each counter uses a fixed window
 * of 24 hours from its own first counted event, not a window aligned to
 * the clock. A counter resets at the first check after its window ends,
 * also when the clock steps backwards (for example after an NTP
 * correction). The global counter and each key counter keep their own
 * window this way, each one independent of the other.
 *
 * <p><strong>The key map.</strong> It holds a maximum of
 * {@value #MAX_KEYS} keys, with an LRU eviction, the form of the key map
 * of {@link IngestRateLimiter}. A dropped batch of a new key takes no
 * slot of this map. A kept batch of a new key takes one slot. It can
 * evict the least recent key. An evicted key starts a fresh window at
 * its next kept batch. The global cap of one day bounds how much such
 * a key can add back (see `kit/jvm-core/README.md`).
 *
 * <p><strong>The warning.</strong> This class writes a maximum of one
 * WARN line for each elapsed hour since the last one. It measures the
 * hour from the last warning, not from the clock hour. Issue #117 adds
 * this rule, after a review of pull request #165 found a defect in an
 * earlier clock-hour throttle. The line names the cap that dropped the
 * batch. It never holds a key, an address, a user agent, or the count
 * of one key. This class holds the log call outside its lock, so a
 * slow log write never stalls a check of a different request.
 *
 * <p>This class is thread-safe. One lock guards the global counter and
 * the key map together, so a check of the two caps and the count of a
 * kept batch run as one atomic step.
 */
public final class AnonymousDailyCap {

    /** The cap of the key map (design decision D43, the form of {@link IngestRateLimiter}). */
    static final int MAX_KEYS = 20_000;

    private static final long WINDOW_MILLIS = Duration.ofHours(24).toMillis();
    private static final long WARN_THROTTLE_MILLIS = Duration.ofHours(1).toMillis();

    private static final Logger LOGGER = System.getLogger("octometer.kit.core");

    private final Clock clock;
    private final long maxGlobalEventsPerDay;
    private final long maxEventsPerKeyPerDay;

    private final Object lock = new Object();
    private final Counter global = new Counter();
    private final LinkedHashMap<String, Counter> keyCounters;

    private long lastWarnedAtMillis = Long.MIN_VALUE;

    /**
     * Builds one daily cap.
     *
     * @param clock the clock of each window. A test gives a fixed or a
     *   mutable clock; an app gives the system clock.
     * @param maxGlobalEventsPerDay the global cap of {@code
     *   OCTOMETER_MAX_ANON_EVENTS_PER_DAY}. It must be 1 or more.
     * @param maxEventsPerKeyPerDay the cap of one key, of {@code
     *   OCTOMETER_ANON_EVENTS_PER_KEY_PER_DAY}. It must be 1 or more.
     */
    public AnonymousDailyCap(Clock clock, long maxGlobalEventsPerDay, long maxEventsPerKeyPerDay) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (maxGlobalEventsPerDay < 1) {
            throw new IllegalArgumentException("maxGlobalEventsPerDay must be 1 or more");
        }
        if (maxEventsPerKeyPerDay < 1) {
            throw new IllegalArgumentException("maxEventsPerKeyPerDay must be 1 or more");
        }
        this.maxGlobalEventsPerDay = maxGlobalEventsPerDay;
        this.maxEventsPerKeyPerDay = maxEventsPerKeyPerDay;
        this.keyCounters = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Counter> eldest) {
                return size() > MAX_KEYS;
            }
        };
    }

    /**
     * Checks one batch of {@code entryCount} entries against the global
     * cap and the cap of {@code key}. It drops the whole batch when the
     * global counter, or the counter of {@code key}, plus {@code
     * entryCount}, would go above its cap. A dropped batch changes no
     * counter, and it takes no slot of the key map for a key this map
     * has not tracked yet. A kept batch adds {@code entryCount} to the
     * global counter and to the counter of {@code key}.
     *
     * @param key the anonymous key of design decision D43 (see
     *   {@link AnonymousKey#of(String)}).
     * @param entryCount the entry count of the batch. It must be 1 or
     *   more.
     * @return {@code true} when the batch passes; {@code false} when it
     *   drops.
     */
    public boolean check(String key, int entryCount) {
        Objects.requireNonNull(key, "key must not be null");
        if (entryCount < 1) {
            throw new IllegalArgumentException("entryCount must be 1 or more");
        }
        long now = clock.millis();
        boolean passed;
        boolean overGlobal;
        boolean dueForWarning = false;
        synchronized (lock) {
            global.resetIfWindowEnded(now);
            Counter keyCounter = keyCounters.get(key);
            if (keyCounter != null) {
                keyCounter.resetIfWindowEnded(now);
            }
            long keyCount = keyCounter == null ? 0 : keyCounter.count;

            overGlobal = global.count + entryCount > maxGlobalEventsPerDay;
            boolean overKey = keyCount + entryCount > maxEventsPerKeyPerDay;
            passed = !overGlobal && !overKey;
            if (passed) {
                if (keyCounter == null) {
                    keyCounter = new Counter();
                    keyCounters.put(key, keyCounter);
                }
                global.add(now, entryCount);
                keyCounter.add(now, entryCount);
            } else {
                dueForWarning = dueForWarning(now);
            }
        }
        // The log call runs after the lock ends, so a slow log write
        // never stalls a check of a different request (Java review
        // MINOR 1, security review M2).
        if (dueForWarning) {
            logWarning(overGlobal);
        }
        return passed;
    }

    /**
     * Returns the key count of the key map. A test uses this method to
     * confirm the map size after an eviction. The class itself needs
     * no such count.
     */
    int keyCount() {
        synchronized (lock) {
            return keyCounters.size();
        }
    }

    /**
     * True at most one time for each elapsed hour since the last true
     * result, measured from {@code now}. This method also records
     * {@code now} as the last warned instant. It must run inside
     * {@link #lock}. Only the log write itself may run outside it.
     */
    private boolean dueForWarning(long now) {
        if (lastWarnedAtMillis != Long.MIN_VALUE && now - lastWarnedAtMillis < WARN_THROTTLE_MILLIS) {
            return false;
        }
        lastWarnedAtMillis = now;
        return true;
    }

    /**
     * Writes one warning. The message names the cap that dropped the
     * batch. It never holds a key, an address, a user agent, or a
     * count.
     */
    private void logWarning(boolean overGlobal) {
        String capName = overGlobal ? "OCTOMETER_MAX_ANON_EVENTS_PER_DAY" : "OCTOMETER_ANON_EVENTS_PER_KEY_PER_DAY";
        LOGGER.log(Level.WARNING, "The daily anonymous cap " + capName + " drops one or more batches "
                + "(design decision D43). The log holds no key, no address, and no user agent.");
    }

    /**
     * One counter: its window start instant and its count. A test never
     * builds this class directly; {@link #check} manages each instance.
     */
    private static final class Counter {
        private long windowStart = Long.MIN_VALUE;
        private long count = 0;

        /**
         * Resets this counter to zero, with no window, when {@code now}
         * is at or after the end of the live window, or before the
         * start of the live window. The second case guards a clock
         * that steps backwards, for example after an NTP correction
         * (Java review MINOR 2): with no guard, such a step would hold
         * this counter past its 24-hour window forever. This counter
         * never resets while {@code now} still sits inside its window.
         */
        void resetIfWindowEnded(long now) {
            if (windowStart != Long.MIN_VALUE && (now >= windowStart + WINDOW_MILLIS || now < windowStart)) {
                windowStart = Long.MIN_VALUE;
                count = 0;
            }
        }

        /**
         * Adds {@code entryCount} to this counter. It starts a new
         * window at {@code now} when this counter holds no window yet
         * (design decision D43: the window starts at the first counted
         * event).
         */
        void add(long now, int entryCount) {
            if (windowStart == Long.MIN_VALUE) {
                windowStart = now;
            }
            count += entryCount;
        }
    }
}
