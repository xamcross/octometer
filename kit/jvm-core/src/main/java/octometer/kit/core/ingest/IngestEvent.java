package octometer.kit.core.ingest;

import java.time.Instant;

/**
 * One validated click, ready for a store to hold.
 * {@link IngestPipeline#ingest ingest} passes the user id to the store as
 * a separate parameter, from the app's
 * {@link octometer.kit.core.user.UserIdResolver} (design decision D18).
 * This record stays as it was in issue #10.
 */
public record IngestEvent(String sessionId, String element, Instant ts) {

    /**
     * Returns a text with no personal data (design decision D15). The
     * session id stays out of this text, because a log line must never
     * hold it.
     */
    @Override
    public String toString() {
        return "IngestEvent[element=" + element + ", ts=" + ts + "]";
    }
}
