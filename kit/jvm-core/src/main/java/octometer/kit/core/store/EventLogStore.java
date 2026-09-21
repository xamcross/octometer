package octometer.kit.core.store;

import java.util.List;
import octometer.kit.core.ingest.IngestEvent;

/**
 * The event log store of design decision D18. An app gives one
 * implementation. This module adds only an in-memory implementation;
 * issue #35 adds {@code deleteByUserId} to the other stores.
 */
public interface EventLogStore {

    /**
     * Appends each valid event of one batch, with the user id from a
     * {@link octometer.kit.core.user.UserIdResolver}. The value of
     * {@code userId} can be {@code null} (design decision D19). The
     * batch is the unit of the write, so a store can drop the whole
     * batch above the event cap of design decision D21 (contract rule
     * C19, issue #34).
     *
     * <p>The list holds one event or more. {@link
     * octometer.kit.core.ingest.IngestPipeline#ingest} never calls this
     * method with an empty list.
     *
     * <p>A store reads the list. It never changes the list, and it
     * never keeps a reference to the list after the call.
     *
     * <p>A store throws an unchecked exception when the write fails. An
     * adapter maps that exception to status 500.
     */
    void append(List<IngestEvent> events, String userId);

    /**
     * Appends one valid event. This default method wraps the event in a
     * batch of one, then calls {@link #append(List, String)}.
     */
    default void append(IngestEvent event, String userId) {
        append(List.of(event), userId);
    }

    /**
     * Deletes each stored event with the given user id. The value of
     * {@code userId} must not be {@code null}, and it must not be an
     * empty text. An implementation throws {@link NullPointerException}
     * for a {@code null} value, and {@link IllegalArgumentException} for
     * an empty text. A store never erases an anonymous event with this
     * method, because that event holds no user id (contract rule C6).
     *
     * <p>A store throws an unchecked exception when the delete fails. An
     * adapter maps that exception to status 500.
     */
    void deleteByUserId(String userId);
}
