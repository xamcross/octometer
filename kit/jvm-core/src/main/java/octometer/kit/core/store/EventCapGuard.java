package octometer.kit.core.store;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * The event cap guard of design decision D21 and issue #34. Correction
 * round 1 of pull request #165 closed three gaps that the security
 * review found. A store asks {@link #isOverCap()} before it appends a
 * batch. It calls {@link #countAppended(long)} after a batch that it
 * does append, and {@link #warnDropOncePerHour()} only when it then
 * drops a batch.
 *
 * <p>This class reads the estimate of an {@link EventCountEstimator} at
 * most one time in each refresh interval, with a cached value between two
 * reads. A store never pays the cost of a real count query on each
 * request this way. Design decision D21 sets the refresh interval at 60
 * seconds.
 *
 * <p><strong>The overshoot inside one refresh interval is bounded
 * (BLOCKER 1).</strong> {@link #countAppended(long)} adds the size of
 * each batch that the store writes to a running total. {@link
 * #isOverCap()} adds that total to the cached estimate. The refresh
 * that follows resets the total to zero. A flood inside one refresh
 * interval therefore still stops at the cap. It stops one batch above
 * the cap, at the most.
 *
 * <p><strong>The estimator call never blocks a second caller
 * (MAJOR 2).</strong> One refresh runs at a time. A caller that starts
 * a refresh waits for the estimator. Each other caller, in the same
 * moment, returns the cached estimate at once. It never waits for that
 * call.
 *
 * <p><strong>A failed read keeps the last good estimate, then fails
 * closed (BLOCKER 1).</strong> Take a database user with too few rights
 * to run the count query, so {@code estimator} throws. {@link
 * #isOverCap()} then keeps the cached estimate of the last successful
 * read. It does not reset the estimate to zero. A success resets the
 * failure count to zero. After {@value #MAX_FAILURES_IN_A_ROW} failures
 * in a row, the guard fails closed: {@link #isOverCap()} returns {@code
 * true} until a read succeeds again. The store then drops each batch,
 * rather than let an unbounded ingest run with no cap. The class writes
 * one warning for each failed read, with the same once-per-hour
 * throttle as a drop. The warning text states the new state: a kept
 * estimate, or a closed guard.
 *
 * <p>{@link #warnDropOncePerHour()} writes one warning for each elapsed
 * hour since the last one, never one for each dropped batch. A
 * sustained overload therefore writes a bounded number of log lines.
 * The warning holds no user id, no client address, and no session id
 * (design decision D15). A dropped batch returns faster than a stored
 * batch, because the store makes no network call for it. A client can
 * measure that difference, but it learns no user data from it.
 *
 * <p>This class is thread-safe. Many callers can call {@link #isOverCap()}
 * at the same time.
 */
public final class EventCapGuard {

    private static final Logger LOGGER = System.getLogger("octometer.kit.core");
    private static final long HOUR_MILLIS = Duration.ofHours(1).toMillis();

    /** The count of count-query failures in a row that fails the guard closed. */
    private static final int MAX_FAILURES_IN_A_ROW = 3;

    private final long maxEvents;
    private final long refreshIntervalMillis;
    private final Clock clock;
    private final EventCountEstimator estimator;
    private final Function<RuntimeException, String> errorCodeOf;

    /** True while one thread reads {@link #estimator}. Guards {@link #snapshot}. */
    private final AtomicBoolean refreshInFlight = new AtomicBoolean(false);
    private volatile Snapshot snapshot = new Snapshot(Long.MIN_VALUE, 0L);

    /** The count of events that {@link #countAppended(long)} added since the last refresh. */
    private final AtomicLong appendedSinceRefresh = new AtomicLong();

    /** The count of count-query failures in a row. A success resets it to zero. */
    private final AtomicInteger failuresInARow = new AtomicInteger();

    private final AtomicLong lastWarnedDropAtMillis = new AtomicLong(Long.MIN_VALUE);

    /**
     * The throttle of a failure warning that still keeps the last good
     * estimate: fewer than {@link #MAX_FAILURES_IN_A_ROW} failures in a
     * row. This counter is separate from {@link
     * #lastWarnedFailClosedAtMillis}. A fail-closed warning therefore
     * still fires the moment the guard first fails closed, even in an
     * hour that already holds a kept-estimate warning.
     */
    private final AtomicLong lastWarnedKeptEstimateAtMillis = new AtomicLong(Long.MIN_VALUE);

    /** The throttle of the fail-closed warning. See {@link #lastWarnedKeptEstimateAtMillis}. */
    private final AtomicLong lastWarnedFailClosedAtMillis = new AtomicLong(Long.MIN_VALUE);

    /**
     * Builds one guard. A failed read never holds the MongoDB error code
     * in its warning; use {@link #EventCapGuard(long, Duration, Clock,
     * EventCountEstimator, Function)} for that.
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
        this(maxEvents, refreshInterval, clock, estimator, cause -> "unknown");
    }

    /**
     * Builds one guard. {@code errorCodeOf} turns a failed read into the
     * text of a warning, for example the numeric MongoDB error code of a
     * {@code MongoCommandException}. This class holds no MongoDB
     * dependency of its own (design decision D22); a store gives its own
     * function.
     *
     * @param maxEvents the cap of design decision D21. It must be 1 or
     *   more.
     * @param refreshInterval the minimum time between two reads of
     *   {@code estimator}. It must be a positive duration.
     * @param clock the clock of the cache. A test gives a fixed or a
     *   mutable clock; an app gives the system clock.
     * @param estimator the cheap count of the store.
     * @param errorCodeOf reads the numeric error code text of a failed
     *   read, or {@code "unknown"} for a cause with no such code.
     */
    public EventCapGuard(long maxEvents, Duration refreshInterval, Clock clock, EventCountEstimator estimator,
            Function<RuntimeException, String> errorCodeOf) {
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
        this.errorCodeOf = Objects.requireNonNull(errorCodeOf, "errorCodeOf must not be null");
    }

    /**
     * True when the cached estimate, plus each event that {@link
     * #countAppended(long)} added since the last refresh, is at or above
     * the cap. This method refreshes the cached estimate at most one
     * time in each refresh interval; a call inside that interval reads
     * the cached value only, with no call of {@link
     * EventCountEstimator#estimatedEventCount()}.
     *
     * <p>Only one caller runs the refresh at a time. Each other caller,
     * in that same moment, reads the previous cached estimate at once
     * and never waits for the refresh.
     */
    public boolean isOverCap() {
        long now = clock.millis();
        Snapshot current = snapshot;
        if (needsRefresh(current, now) && refreshInFlight.compareAndSet(false, true)) {
            try {
                long refreshedEstimate = readEstimateOrKeepLastGood(now, current.estimate());
                current = new Snapshot(now, refreshedEstimate);
                snapshot = current;
                appendedSinceRefresh.set(0);
            } finally {
                refreshInFlight.set(false);
            }
        }
        return current.estimate() + appendedSinceRefresh.get() >= maxEvents;
    }

    /**
     * Adds {@code eventCount} to the count of events that a store wrote
     * since the last refresh. A store calls this method only after it
     * writes a batch that {@link #isOverCap()} let through. {@link
     * #isOverCap()} adds this running total to the cached estimate, so a
     * flood inside one refresh interval stops at the cap too.
     */
    public void countAppended(long eventCount) {
        if (eventCount > 0) {
            appendedSinceRefresh.addAndGet(eventCount);
        }
    }

    private long readEstimateOrKeepLastGood(long now, long lastGoodEstimate) {
        try {
            long estimate = estimator.estimatedEventCount();
            failuresInARow.set(0);
            return estimate;
        } catch (RuntimeException cause) {
            int failures = failuresInARow.incrementAndGet();
            String errorCode = errorCodeOf.apply(cause);
            if (failures >= MAX_FAILURES_IN_A_ROW) {
                warnOncePerHour(lastWarnedFailClosedAtMillis, now, "The event log store could not read its "
                        + "event count for the cap of design decision D21, for the " + failures + " time "
                        + "in a row (MongoDB error code " + errorCode + "). The store now fails closed: "
                        + "it drops each batch until a count succeeds. The log holds no user id, no "
                        + "client address, and no session id.");
                return maxEvents;
            }
            warnOncePerHour(lastWarnedKeptEstimateAtMillis, now, "The event log store could not read its "
                    + "event count for the cap of design decision D21 (MongoDB error code " + errorCode
                    + "). It keeps the last known count until the next refresh. The log holds no user id, "
                    + "no client address, and no session id.");
            return lastGoodEstimate;
        }
    }

    private boolean needsRefresh(Snapshot current, long now) {
        return current.checkedAtMillis() == Long.MIN_VALUE
                || now - current.checkedAtMillis() >= refreshIntervalMillis
                || now < current.checkedAtMillis();
    }

    /**
     * Writes one warning for each elapsed hour since the last one, at
     * most one time. A store calls this method only when it drops a
     * batch because {@link #isOverCap()} gave {@code true}. The message
     * names no user id, no client address, and no session id (design
     * decision D15).
     */
    public void warnDropOncePerHour() {
        warnOncePerHour(lastWarnedDropAtMillis, clock.millis(), "The event log store drops one or more "
                + "batches because the event count is at or above the cap of design decision D21. The "
                + "log holds no user id, no client address, and no session id.");
    }

    /**
     * Writes {@code message} at most one time in each elapsed hour,
     * tracked in {@code lastWarnedAtMillis}. The throttle uses the
     * elapsed time since the last warning, not the UTC clock hour. Two
     * warnings across a clock-hour boundary therefore still count as
     * one (MINOR of both reviews of pull request #165).
     *
     * <p>Three counters track three kinds of warning: {@link
     * #lastWarnedDropAtMillis}, {@link #lastWarnedKeptEstimateAtMillis},
     * and {@link #lastWarnedFailClosedAtMillis}. Each counter throttles
     * on its own. One kind of warning can never hide another kind. The
     * first fail-closed warning always fires, even in an hour that
     * already holds a kept-estimate warning.
     */
    private static void warnOncePerHour(AtomicLong lastWarnedAtMillis, long now, String message) {
        long previouslyWarnedAtMillis = lastWarnedAtMillis.get();
        if (previouslyWarnedAtMillis != Long.MIN_VALUE && now - previouslyWarnedAtMillis < HOUR_MILLIS) {
            return;
        }
        if (lastWarnedAtMillis.compareAndSet(previouslyWarnedAtMillis, now)) {
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
