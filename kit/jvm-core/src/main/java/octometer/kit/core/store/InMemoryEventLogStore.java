package octometer.kit.core.store;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import octometer.kit.core.ingest.IngestEvent;

/**
 * An in-memory {@link EventLogStore}. A call from more than one thread is
 * safe.
 *
 * <p>Use this store only in a test and in a demo. It holds each event in
 * the heap, with no size limit, and it loses each event at a restart. An
 * app in production uses the MongoDB store of issue #11.
 */
public final class InMemoryEventLogStore implements EventLogStore {

    private final ConcurrentLinkedQueue<StoredEvent> storedEvents = new ConcurrentLinkedQueue<>();

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
     * (contract rule C43). The call reads the session ids of this user
     * first, then removes the two groups of events. The two steps are
     * not one atomic step: a concurrent {@link #append} for a new
     * session of this user, between the read and the removals, keeps
     * its anonymous events in the store.
     */
    @Override
    public DeletionResult deleteByUserId(String userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        if (userId.isEmpty()) {
            throw new IllegalArgumentException("userId must not be an empty text");
        }
        Set<String> sessionIds = new HashSet<>();
        for (StoredEvent storedEvent : storedEvents) {
            if (userId.equals(storedEvent.userId())) {
                sessionIds.add(storedEvent.sessionId());
            }
        }
        long userEventCount = removeMatching(storedEvent -> userId.equals(storedEvent.userId()));
        long anonymousEventCount = removeMatching(
                storedEvent -> storedEvent.userId() == null && sessionIds.contains(storedEvent.sessionId()));
        return new DeletionResult(userEventCount, anonymousEventCount);
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
}
