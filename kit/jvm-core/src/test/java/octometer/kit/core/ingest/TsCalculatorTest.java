package octometer.kit.core.ingest;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests of the `ts` rule (design D18, C14, C15): `ts` is `receivedAt`
 * minus `ageMs`, with a clamp of `ageMs` above 600000, and a rejection of
 * a negative `ageMs`.
 */
class TsCalculatorTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-09-21T10:15:30.000Z");

    @Test
    void computesTsWithAFixedClock() {
        Instant ts = TsCalculator.computeTs(RECEIVED_AT, 1200L);

        assertEquals(Instant.parse("2026-09-21T10:15:28.800Z"), ts);
    }

    @Test
    void clampsAnAgeMsAbove600000() {
        Instant ts = TsCalculator.computeTs(RECEIVED_AT, 700_000L);

        assertEquals(RECEIVED_AT.minusMillis(600_000L), ts);
    }

    @Test
    void keepsAnAgeMsAt600000Unclamped() {
        Instant ts = TsCalculator.computeTs(RECEIVED_AT, 600_000L);

        assertEquals(RECEIVED_AT.minusMillis(600_000L), ts);
    }

    @Test
    void rejectsANegativeAgeMs() {
        IngestException error = assertThrows(IngestException.class, () -> TsCalculator.computeTs(RECEIVED_AT, -1L));

        assertEquals(IngestException.Reason.NEGATIVE_AGE_MS, error.reason());
    }
}
