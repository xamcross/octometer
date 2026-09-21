package octometer.kit.core.store;

import octometer.kit.core.ingest.IngestEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Tests of {@link StoredEvent#toString()} against design decision D15: a
 * log line never holds a user id or a session id.
 */
class StoredEventTest {

    @Test
    void toStringHoldsNoUserIdAndNoSessionId() {
        String sessionId = "3fa85f64-5717-4562-b3fc-2c963f66afa6";
        String userId = "internal-user-42";
        IngestEvent event = new IngestEvent(sessionId, "checkout.save", Instant.parse("2026-09-21T10:15:30.000Z"));
        StoredEvent stored = StoredEvent.of(event, userId);

        String text = stored.toString();

        assertFalse(text.contains(userId), "the text must not hold the user id");
        assertFalse(text.contains(sessionId), "the text must not hold the session id");
    }
}
