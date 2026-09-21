package octometer.kit.core.store;

import java.time.Instant;
import octometer.kit.core.ingest.IngestEvent;

/**
 * One event that a store holds: the fields of {@link IngestEvent}, plus
 * the user id (contract rule C6).
 *
 * <p>Version 1.1 of the contract adds `referrerHost` (rule C40, issue
 * #103). This record does not hold `path`: rule C42 says the server
 * stores no `path` field without a route pattern list, and this module
 * has no such list yet. Issue #104 adds the list, the match, and the
 * `path` field of this record.
 */
public record StoredEvent(String sessionId, String element, Instant ts, String userId, String referrerHost) {

    /**
     * Builds one {@link StoredEvent} out of one {@link IngestEvent} and
     * the resolved user id. This method copies {@link
     * IngestEvent#referrerHost()}, but not {@link IngestEvent#path()},
     * for the reason of the class comment above.
     */
    public static StoredEvent of(IngestEvent event, String userId) {
        return new StoredEvent(event.sessionId(), event.element(), event.ts(), userId, event.referrerHost());
    }

    /**
     * Returns a text with no personal data (design decision D15). The
     * user id and the session id stay out of this text, because a log
     * line must never hold them.
     */
    @Override
    public String toString() {
        return "StoredEvent[element=" + element + ", ts=" + ts
                + ", userId=" + (userId == null ? "null" : "<redacted>") + "]";
    }
}
