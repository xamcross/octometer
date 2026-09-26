package com.octometer.smoke;

import java.util.ArrayList;
import java.util.List;
import octometer.kit.core.ingest.IngestEvent;
import octometer.kit.core.store.DeletionResult;
import octometer.kit.core.store.EventLogStore;

/**
 * A test-only {@link EventLogStore} of issue #68 (release review of pull
 * request #200, MAJOR 2). It keeps each event in memory. The test needs
 * no MongoDB server for this store.
 */
final class RecordingEventLogStore implements EventLogStore {

    private final List<IngestEvent> keptEvents = new ArrayList<>();

    @Override
    public void append(List<IngestEvent> events, String userId) {
        keptEvents.addAll(events);
    }

    @Override
    public DeletionResult deleteByUserId(String userId) {
        throw new UnsupportedOperationException("This test store deletes no event.");
    }

    List<IngestEvent> keptEvents() {
        return keptEvents;
    }
}
