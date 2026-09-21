package octometer.kit.core.store;

import java.time.Instant;
import octometer.kit.core.ingest.IngestEvent;

/**
 * One event that a store holds: the fields of {@link IngestEvent}, plus
 * the user id (contract rule C6).
 */
public record StoredEvent(String sessionId, String element, Instant ts, String userId) {

    /**
     * Builds one {@link StoredEvent} out of one {@link IngestEvent} and
     * the resolved user id.
     */
    public static StoredEvent of(IngestEvent event, String userId) {
        return new StoredEvent(event.sessionId(), event.element(), event.ts(), userId);
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
