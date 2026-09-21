package octometer.kit.core.store;

import octometer.kit.core.ingest.IngestEvent;

/**
 * The event log store of design decision D18. An app gives one
 * implementation. This module adds only an in-memory implementation;
 * issue #35 adds {@code deleteByUserId} to the other stores.
 */
public interface EventLogStore {

    /**
     * Appends one valid event, with the user id from a
     * {@link octometer.kit.core.user.UserIdResolver}. The value of
     * {@code userId} can be {@code null} (design decision D19).
     */
    void append(IngestEvent event, String userId);

    /**
     * Deletes each stored event with the given user id.
     */
    void deleteByUserId(String userId);
}
