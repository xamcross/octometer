package octometer.kit.core.store;

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
        for (IngestEvent event : events) {
            Objects.requireNonNull(event, "event must not be null");
            storedEvents.add(StoredEvent.of(event, userId));
        }
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
