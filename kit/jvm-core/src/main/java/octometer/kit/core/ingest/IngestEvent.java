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
}
