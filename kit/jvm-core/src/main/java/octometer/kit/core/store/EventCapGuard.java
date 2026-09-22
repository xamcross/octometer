package octometer.kit.core.store;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The event cap guard of design decision D21 and issue #34. A store asks
 * {@link #isOverCap()} before it appends a batch, and it calls
 * {@link #warnDropOncePerHour()} only when it then drops the batch.
 *
 * <p>This class reads the estimate of an {@link EventCountEstimator} at
 * most one time in each refresh interval, with a cached value between two
 * reads. A store never pays the cost of a real count query on each
 * request this way. Between two reads, the cached estimate can miss up
 * to one refresh interval of new events above the cap; a deployment
 * accepts this small overshoot in exchange for zero per-request count
 * queries. Design decision D21 sets the refresh interval at 60 seconds,
 * so the largest overshoot is the write volume of one such interval,
 * with no upper bound of its own on that volume.
 *
 * <p><strong>A failed read fails open.</strong> When {@code estimator}
 * throws, for example a database user with too few rights to run the
 * count query, {@link #isOverCap()} treats that refresh as "not over
 * cap" and tries the estimator again after the next refresh interval. A
 * count failure must never turn into a full ingest outage; issue #34
 * found this exact failure against a MongoDB user with an insert-only
 * role. The class writes one warning for the failure, with the same
 * once-per-hour throttle as a drop.
 *
 * <p>{@link #warnDropOncePerHour()} writes one warning for each 60-minute
 * span, never one for each dropped batch, so a sustained overload writes
 * a bounded number of log lines. The warning holds no user id, no client
 * address, and no session id (design decision D15).
 *
 * <p>This class is thread-safe. Many callers can call {@link #isOverCap()}
 * at the same time.
 */
public final class EventCapGuard {

    private static final Logger LOGGER = System.getLogger("octometer.kit.core");
    private static final long HOUR_MILLIS = Duration.ofHours(1).toMillis();

    private final long maxEvents;
    private final long refreshIntervalMillis;
    private final Clock clock;
    private final EventCountEstimator estimator;

    /** Guards a change of {@link #snapshot} from one refresh to the next. */
    private final Object refreshLock = new Object();
    private volatile Snapshot snapshot = new Snapshot(Long.MIN_VALUE, 0L);
    private final AtomicLong lastWarnedDropHourStart = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong lastWarnedFailureHourStart = new AtomicLong(Long.MIN_VALUE);

    /**
     * Builds one guard.
     *
     * @param maxEvents the cap of design decision D21. It must be 1 or
     *   more.
     * @param refreshInterval the minimum time between two reads of
     *   {@code estimator}. It must be a positive duration.
     * @param clock the clock of the cache. A test gives a fixed or a
     *   mutable clock; an app gives the system clock.
     * @param estimator the cheap count of the store.
     */
    public EventCapGuard(long maxEvents, Duration refreshInterval, Clock clock, EventCountEstimator estimator) {
        if (maxEvents < 1) {
            throw new IllegalArgumentException("maxEvents must be 1 or more");
        }
        Objects.requireNonNull(refreshInterval, "refreshInterval must not be null");
        if (refreshInterval.isZero() || refreshInterval.isNegative()) {
            throw new IllegalArgumentException("refreshInterval must be a positive duration");
        }
        this.maxEvents = maxEvents;
        this.refreshIntervalMillis = refreshInterval.toMillis();
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.estimator = Objects.requireNonNull(estimator, "estimator must not be null");
    }

    /**
     * True when the cached estimate is at or above the cap. This method
     * refreshes the cached estimate at most one time in each refresh
     * interval; a call inside that interval reads the cached value only,
     * and it never calls {@link EventCountEstimator#estimatedEventCount()}.
     *
     * <p>When the refresh itself throws, this method fails open (see the
     * class comment): it caches an estimate of zero for this interval, it
     * writes one warning for the current hour, and it returns
     * {@code false}.
     */
    public boolean isOverCap() {
        long now = clock.millis();
        Snapshot current = snapshot;
        if (needsRefresh(current, now)) {
            synchronized (refreshLock) {
                current = snapshot;
                if (needsRefresh(current, now)) {
                    current = new Snapshot(now, readEstimateOrFailOpen(now));
                    snapshot = current;
                }
            }
        }
        return current.estimate() >= maxEvents;
    }

    private long readEstimateOrFailOpen(long now) {
        try {
            return estimator.estimatedEventCount();
        } catch (RuntimeException cause) {
            warnOncePerHour(lastWarnedFailureHourStart, now, "The event log store could not read its event "
                    + "count for the cap of design decision D21. It treats the count as unknown and "
                    + "accepts each batch until the next refresh. The log holds no user id, no client "
                    + "address, and no session id.");
            return 0L;
        }
    }

    private boolean needsRefresh(Snapshot current, long now) {
        return current.checkedAtMillis() == Long.MIN_VALUE
                || now - current.checkedAtMillis() >= refreshIntervalMillis;
    }

    /**
     * Writes one warning for the current 60-minute span, at most one
     * time. A store calls this method only when it drops a batch because
     * {@link #isOverCap()} gave {@code true}. The message names no user
     * id, no client address, and no session id (design decision D15).
     */
    public void warnDropOncePerHour() {
        warnOncePerHour(lastWarnedDropHourStart, clock.millis(), "The event log store drops one or more "
                + "batches because the event count is at or above the cap of design decision D21. The "
                + "log holds no user id, no client address, and no session id.");
    }

    /**
     * Writes {@code message} at most one time for the 60-minute span that
     * holds {@code now}, tracked in {@code lastWarnedHourStart}. Two
     * different counters ({@link #lastWarnedDropHourStart} and
     * {@link #lastWarnedFailureHourStart}) keep a drop warning and a
     * failure warning independent, so one never suppresses the other.
     */
    private static void warnOncePerHour(AtomicLong lastWarnedHourStart, long now, String message) {
        long hourStart = now - Math.floorMod(now, HOUR_MILLIS);
        long previouslyWarnedHourStart = lastWarnedHourStart.get();
        if (previouslyWarnedHourStart == hourStart) {
            return;
        }
        if (lastWarnedHourStart.compareAndSet(previouslyWarnedHourStart, hourStart)) {
            LOGGER.log(Level.WARNING, message);
        }
    }

    /**
     * One cached read: the clock instant of the read, and the estimate
     * of that read. This class holds the pair in one {@code volatile}
     * field, so a reader always sees one matching instant and estimate
     * together.
     */
    private record Snapshot(long checkedAtMillis, long estimate) {
    }
}
