package octometer.kit.core.store;

import octometer.kit.core.ingest.IngestEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests of {@link StoredEvent} against design decision D15: a log line
 * never holds a user id or a session id. Version 1.1 of the contract adds
 * `referrerHost` and `path` (issue #103). {@link StoredEvent#of} copies
 * both fields from the {@link IngestEvent}. This module has no route
 * pattern list yet, so {@code path} is always {@code null} today; issue
 * #104 adds that list.
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

    @Test
    void ofCopiesTheReferrerHostFromTheIngestEvent() {
        IngestEvent event = new IngestEvent("3fa85f64-5717-4562-b3fc-2c963f66afa6", "octo:session-start",
                Instant.parse("2026-09-21T10:15:30.000Z"), "/some-path", "google.com");

        StoredEvent stored = StoredEvent.of(event, null);

        assertEquals("google.com", stored.referrerHost());
    }

    @Test
    void ofGivesNoReferrerHostWhenTheIngestEventHasNone() {
        IngestEvent event = new IngestEvent("3fa85f64-5717-4562-b3fc-2c963f66afa6", "checkout.save",
                Instant.parse("2026-09-21T10:15:30.000Z"));

        StoredEvent stored = StoredEvent.of(event, null);

        assertNull(stored.referrerHost());
    }

    @Test
    void ofCopiesThePathFromTheIngestEvent() {
        IngestEvent event = new IngestEvent("3fa85f64-5717-4562-b3fc-2c963f66afa6", "checkout.save",
                Instant.parse("2026-09-21T10:15:30.000Z"), "/masked/:id", null);

        StoredEvent stored = StoredEvent.of(event, null);

        assertEquals("/masked/:id", stored.path());
    }

    @Test
    void ofGivesNoPathWhenTheIngestEventHasNone() {
        IngestEvent event = new IngestEvent("3fa85f64-5717-4562-b3fc-2c963f66afa6", "checkout.save",
                Instant.parse("2026-09-21T10:15:30.000Z"));

        StoredEvent stored = StoredEvent.of(event, null);

        assertNull(stored.path());
    }

    @Test
    void theFourArgumentConstructorGivesNoPathAndNoReferrerHost() {
        StoredEvent stored = new StoredEvent("3fa85f64-5717-4562-b3fc-2c963f66afa6", "checkout.save",
                Instant.parse("2026-09-21T10:15:30.000Z"), "user-1");

        assertNull(stored.path());
        assertNull(stored.referrerHost());
    }
}
