package octometer.kit.core.ingest;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Tests of {@link IngestEvent#toString()} against design decision D15: a
 * log line never holds a session id. Version 1.1 of the contract adds
 * `path` and `referrerHost` (issue #103); a log line must not hold them
 * either.
 */
class IngestEventTest {

    @Test
    void toStringHoldsNoSessionId() {
        String sessionId = "3fa85f64-5717-4562-b3fc-2c963f66afa6";
        IngestEvent event = new IngestEvent(sessionId, "checkout.save", Instant.parse("2026-09-21T10:15:30.000Z"));

        String text = event.toString();

        assertFalse(text.contains(sessionId), "the text must not hold the session id");
    }

    @Test
    void toStringHoldsNoPathAndNoReferrerHost() {
        IngestEvent event = new IngestEvent("3fa85f64-5717-4562-b3fc-2c963f66afa6", "checkout.save",
                Instant.parse("2026-09-21T10:15:30.000Z"), "/secret-token-path", "google.com");

        String text = event.toString();

        assertFalse(text.contains("/secret-token-path"), "the text must not hold the path");
        assertFalse(text.contains("google.com"), "the text must not hold the referrer host");
    }
}
