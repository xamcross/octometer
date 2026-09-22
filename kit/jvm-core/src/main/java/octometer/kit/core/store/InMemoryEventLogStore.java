package octometer.kit.core.store;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import octometer.kit.core.ingest.IngestEvent;

/**
 * An in-memory {@link EventLogStore}. A call from more than one thread is
 * safe.
 *
 * <p>Use this store only in a test and in a demo. It holds each event in
 * the heap, with no size limit, and it loses each event at a restart. An
 * app in production uses the MongoDB store of issue #11.
 *
 * <p>This store also implements {@link EventCountEstimator} (issue #34),
 * so a test of {@link EventCapGuard} can use it with no MongoDB
 * dependency. {@link #estimatedEventCount()} walks the whole queue; that
 * cost is fine for a test and a demo store, never for production.
 *
 * <p>{@link #append} takes no lock; a call from more than one thread runs
 * at the same time. {@link #deleteByUserId} takes one lock for its whole
 * body, so two calls of that method never run at the same time. Two
 * parallel calls with the same user id would otherwise both count an
 * event that only one call removes (a weak point of {@link
 * ConcurrentLinkedQueue#removeIf}); the lock keeps each count exact.
 */
public final class InMemoryEventLogStore implements EventLogStore, EventCountEstimator {

    /** The bound on the pass count of {@link #deleteByUserId}. */
    private static final int MAX_DELETE_PASSES = 3;

    private final ConcurrentLinkedQueue<StoredEvent> storedEvents = new ConcurrentLinkedQueue<>();
    private final Object deleteLock = new Object();

    /**
     * A hook for a test, run right after step (a) of {@link
     * #deleteByUserId} and before step (b). The default body does
     * nothing. {@link #setAfterFirstSessionReadHookForTest} replaces
     * it. This field stays in the production class, not in a test
     * subclass. The class is {@code final}. One lock must guard the
     * whole body of {@link #deleteByUserId}. A subclass cannot add a
     * step inside that lock. MINOR 7 of the second privacy review of
     * pull request #157 raises this point.
     */
    private Runnable afterFirstSessionReadHook = () -> { };

    /**
     * A hook for a test, run right before step (c) of one pass of
     * {@link #deleteByUserId}. The default body does nothing. {@link
     * #setBeforeUserEventsRemovedHookForTest} replaces it. See the
     * Javadoc of {@link #afterFirstSessionReadHook} for the reason
     * that this field stays in the production class.
     */
    private IntConsumer beforeUserEventsRemovedHook = pass -> { };

    @Override
    public void append(List<IngestEvent> events, String userId) {
        Objects.requireNonNull(events, "events must not be null");
        // Build the whole batch first, so a bad element (for example a
        // null one) rejects the call before this store changes state.
        // ConcurrentLinkedQueue.addAll then links the whole batch with
        // one compare-and-set, so a reader never sees a part of it.
        List<StoredEvent> batch = new ArrayList<>(events.size());
        for (IngestEvent event : events) {
            Objects.requireNonNull(event, "event must not be null");
            batch.add(StoredEvent.of(event, userId));
        }
        storedEvents.addAll(batch);
    }

    /**
     * Implements the rule of {@link EventLogStore#deleteByUserId}
     * (contract rule C43, correction round 1 of issue #35). The call
     * reads the session ids of this user, deletes the anonymous events
     * of those sessions, then deletes the user's own events of those
     * sessions. It reads the session ids again, and it repeats the two
     * deletes for a new session id, up to {@link #MAX_DELETE_PASSES}
     * passes. The anonymous delete of a pass always runs before the
     * user delete of the same pass, so a new session of this user that
     * starts during the call never strands an anonymous event.
     */
    @Override
    public DeletionResult deleteByUserId(String userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        if (userId.isEmpty()) {
            throw new IllegalArgumentException("userId must not be an empty text");
        }
        synchronized (deleteLock) {
            long userEventCount = 0;
            long anonymousEventCount = 0;
            Set<String> sessionIds = sessionIdsOf(userId);
            afterFirstSessionReadHook.run();
            for (int pass = 0; !sessionIds.isEmpty() && pass < MAX_DELETE_PASSES; pass++) {
                Set<String> passSessionIds = sessionIds;
                anonymousEventCount += removeMatching(storedEvent -> storedEvent.userId() == null
                        && passSessionIds.contains(storedEvent.sessionId()));
                beforeUserEventsRemovedHook.accept(pass);
                userEventCount += removeMatching(
                        storedEvent -> userId.equals(storedEvent.userId()) && passSessionIds.contains(storedEvent.sessionId()));
                sessionIds = sessionIdsOf(userId);
            }
            return new DeletionResult(userEventCount, anonymousEventCount, sessionIds.isEmpty());
        }
    }

    private Set<String> sessionIdsOf(String userId) {
        Set<String> sessionIds = new HashSet<>();
        for (StoredEvent storedEvent : storedEvents) {
            if (userId.equals(storedEvent.userId())) {
                sessionIds.add(storedEvent.sessionId());
            }
        }
        return sessionIds;
    }

    /**
     * Replaces the hook that runs after step (a) of {@link
     * #deleteByUserId}. A test uses this hook to insert a new session's
     * events between the session read and the two deletes, and to prove
     * that the pass loop finds the new session on a later pass.
     * Production code must never call this method. Each app builds
     * its store with the no-argument constructor. The default no-op
     * hook then stays in place.
     */
    void setAfterFirstSessionReadHookForTest(Runnable hook) {
        this.afterFirstSessionReadHook = Objects.requireNonNull(hook);
    }

    /**
     * Replaces the hook that runs before step (c) of one pass of {@link
     * #deleteByUserId}. A test uses this hook to throw once, and to
     * prove that the anonymous events of step (b) stay removed, and
     * that a second call finishes the user delete that the throw
     * stopped. Production code must never call this method. Each app
     * builds its store with the no-argument constructor. The default
     * no-op hook then stays in place.
     */
    void setBeforeUserEventsRemovedHookForTest(IntConsumer hook) {
        this.beforeUserEventsRemovedHook = Objects.requireNonNull(hook);
    }

    /**
     * Removes each stored event that matches {@code predicate}, and
     * returns the removed count. A store never logs the removed events
     * (design decision D15).
     */
    private long removeMatching(Predicate<StoredEvent> predicate) {
        AtomicLong removedCount = new AtomicLong();
        storedEvents.removeIf(storedEvent -> {
            if (predicate.test(storedEvent)) {
                removedCount.incrementAndGet();
                return true;
            }
            return false;
        });
        return removedCount.get();
    }

    /**
     * Returns a snapshot of each stored event, in append order.
     */
    public List<StoredEvent> events() {
        return List.copyOf(storedEvents);
    }

    /**
     * Returns the exact count of each stored event (issue #34). This
     * store gives an exact value, not a real estimate, because it holds
     * every event in the heap already.
     */
    @Override
    public long estimatedEventCount() {
        return storedEvents.size();
    }
}
