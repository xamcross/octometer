package octometer.kit.core.ingest;

import java.time.Instant;

/**
 * One validated click, ready for a store to hold. Issue #26 adds the
 * `userId` value, from the app's `UserIdResolver`.
 */
public record IngestEvent(String sessionId, String element, Instant ts) {
}
