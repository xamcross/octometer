package octometer.kit.core.store;

import java.util.List;
import octometer.kit.core.ingest.IngestEvent;

/**
 * The event log store of design decision D18. An app gives one
 * implementation. This module adds only an in-memory implementation.
 */
public interface EventLogStore {

    /**
     * Appends each valid event of one batch, with the user id from a
     * {@link octometer.kit.core.user.UserIdResolver}. The value of
     * {@code userId} can be {@code null} (design decision D19).
     *
     * <p>A store writes the whole batch as one unit when it can. A store
     * that cannot do that can write the first part of the batch, and it
     * then throws. The delivery of an event is at least once (contract
     * rule C24): a caller can see the same event two times, but a store
     * never drops an event that it once wrote.
     *
     * <p>A store can also drop the whole batch above the event cap of
     * design decision D21 (contract rule C19, issue #34).
     *
     * <p>The list holds one event or more. {@link
     * octometer.kit.core.ingest.IngestPipeline#ingest} never calls this
     * method with an empty list.
     *
     * <p>A store reads the list. It never changes the list, and it
     * never keeps a reference to the list after the call.
     *
     * <p><strong>A store writes {@link IngestEvent#path()} as it is.</strong>
     * That component holds only the match result of rules C39 and C42,
     * never the raw client path. This module has no route pattern list
     * yet, so the value is always {@code null} today. Rule C39 says the
     * server never stores the raw client value, and rule C42 says the
     * server stores no `path` field without a route pattern list. Issue
     * #104 adds the list and the match. A raw path can hold an
     * identifier, a token, or a search term (contract rule C42).
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
     * Deletes each stored event with the given user id, and each stored
     * event with {@code userId: null} of a session of that user
     * (contract rule C43, the design change of 2026-09-22 for issue #35,
     * correction round 1). An event of a second user id in the same
     * session stays.
     *
     * <p>An implementation runs these steps in order:
     * <ul>
     * <li>Step (a): it reads the session ids of the given user id.</li>
     * <li>Step (b): it deletes each anonymous event of those sessions.</li>
     * <li>Step (c): it deletes each event with the given user id of
     * those sessions.</li>
     * <li>Step (d): it reads the session ids again.</li>
     * </ul>
     * When a new session id appears at step (d), the implementation
     * repeats steps (b) to (d), up to 3 passes in total. The anonymous
     * delete of a pass always runs before the user delete of the same
     * pass.
     *
     * <p>This order protects the anonymous events. A user event is the
     * only link from a session id to the given user id. A pass never
     * deletes that link before it deletes the anonymous events. A
     * failed delete, or a new session that starts during the call, can
     * therefore never strand an anonymous event beyond a retry's reach.
     *
     * <p>A MongoDB store must not run these steps as one transaction;
     * its constructor takes only a {@code MongoDatabase}, with no client
     * session (design decision D22).
     *
     * <p>The return value holds {@link DeletionResult#complete()}. The
     * value is {@code false} when the pass bound stopped the call while
     * a new session id still existed. A caller runs this method again
     * when the value is {@code false}, and also after a failed call.
     *
     * <p>The value of {@code userId} must not be {@code null}, and it
     * must not be an empty text. An implementation throws {@link
     * NullPointerException} for a {@code null} value, and {@link
     * IllegalArgumentException} for an empty text.
     *
     * <p>The return value holds only the two counts and the flag of
     * {@link DeletionResult}. A store must never put a user id or a
     * session id into a log line, an exception message, or the return
     * value.
     *
     * <p>A store throws an unchecked exception when a delete fails. An
     * adapter maps that exception to status 500.
     *
     * <p>For an app team: run this call before the erasure route of the
     * monitor. Wait for one full poll cycle of the monitor after this
     * call, then call the monitor route. Design decision D15 states this
     * order. A call to the monitor route before that wait lets a poll
     * cycle read the erased events again from this store.
     */
    DeletionResult deleteByUserId(String userId);
}
