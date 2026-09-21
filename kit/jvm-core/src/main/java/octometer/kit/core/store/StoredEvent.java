package octometer.kit.core.store;

import java.time.Instant;
import octometer.kit.core.ingest.IngestEvent;

/**
 * One event that a store holds: the fields of {@link IngestEvent}, plus
 * the user id (contract rule C6).
 *
 * <p>Version 1.1 of the contract adds `referrerHost` (rule C40) and
 * `path` (rule C39, issue #103). {@link #of} copies both fields from
 * the {@link IngestEvent}. The `path` component holds only the match
 * result of rules C39 and C42, never the raw client value, the same
 * rule as {@link IngestEvent#path()}. This module has no route pattern
 * list yet, so this component is always {@code null} today. Issue #104
 * adds the list and the match.
 */
public record StoredEvent(String sessionId, String element, Instant ts, String userId, String path,
        String referrerHost) {

    /**
     * Builds an event with no `path` and no `referrerHost`, the shape of
     * this record before version 1.1 of the contract. A caller from
     * before issue #103 still compiles with this constructor.
     */
    public StoredEvent(String sessionId, String element, Instant ts, String userId) {
        this(sessionId, element, ts, userId, null, null);
    }

    /**
     * Builds one {@link StoredEvent} out of one {@link IngestEvent} and
     * the resolved user id. This method copies {@link IngestEvent#path()}
     * and {@link IngestEvent#referrerHost()}. Neither field holds a raw
     * client value; see the class comment above.
     */
    public static StoredEvent of(IngestEvent event, String userId) {
        return new StoredEvent(event.sessionId(), event.element(), event.ts(), userId, event.path(),
                event.referrerHost());
    }

    /**
     * Returns a text with no personal data (design decision D15). The
     * user id, the session id, and the path stay out of this text,
     * because a log line must never hold them.
     */
    @Override
    public String toString() {
        return "StoredEvent[element=" + element + ", ts=" + ts
                + ", userId=" + (userId == null ? "null" : "<redacted>") + "]";
    }
}
