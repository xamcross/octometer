package octometer.kit.core.ingest;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Tests of {@link IngestEvent#toString()} against design decision D15: a
 * log line never holds a session id.
 */
class IngestEventTest {

    @Test
    void toStringHoldsNoSessionId() {
        String sessionId = "3fa85f64-5717-4562-b3fc-2c963f66afa6";
        IngestEvent event = new IngestEvent(sessionId, "checkout.save", Instant.parse("2026-09-21T10:15:30.000Z"));

        String text = event.toString();

        assertFalse(text.contains(sessionId), "the text must not hold the session id");
    }
}
