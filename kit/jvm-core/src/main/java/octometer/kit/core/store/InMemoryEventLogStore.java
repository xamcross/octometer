package octometer.kit.core.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
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

    @Override
    public void deleteByUserId(String userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        if (userId.isEmpty()) {
            throw new IllegalArgumentException("userId must not be an empty text");
        }
        storedEvents.removeIf(storedEvent -> userId.equals(storedEvent.userId()));
    }

    /**
     * Returns a snapshot of each stored event, in append order.
     */
    public List<StoredEvent> events() {
        return List.copyOf(storedEvents);
    }
}
