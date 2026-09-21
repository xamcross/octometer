package octometer.kit.core.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import octometer.kit.core.ingest.IngestEvent;

/**
 * An in-memory {@link EventLogStore}. A call from more than one thread is
 * safe. This store holds no size limit. Issue #34 adds the event cap
 * {@code OCTOMETER_MAX_EVENTS}; that limit is out of scope here.
 */
public final class InMemoryEventLogStore implements EventLogStore {

    private final ConcurrentLinkedQueue<StoredEvent> events = new ConcurrentLinkedQueue<>();

    @Override
    public void append(IngestEvent event, String userId) {
        Objects.requireNonNull(event, "event must not be null");
        events.add(StoredEvent.of(event, userId));
    }

    @Override
    public void deleteByUserId(String userId) {
        events.removeIf(storedEvent -> Objects.equals(storedEvent.userId(), userId));
    }

    /**
     * Returns a snapshot of each stored event, in append order.
     */
    public List<StoredEvent> events() {
        return List.copyOf(new ArrayList<>(events));
    }
}
