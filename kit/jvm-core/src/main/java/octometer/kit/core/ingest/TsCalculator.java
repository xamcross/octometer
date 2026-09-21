package octometer.kit.core.ingest;

import java.time.Instant;

/**
 * The `ts` rule of design decision D18 and rules C14, C15: `ts` is
 * `receivedAt` minus `ageMs`, with a clamp of `ageMs` above 600000
 * milliseconds. A negative `ageMs` is invalid.
 */
public final class TsCalculator {

    /** The clamp of rule C14, in milliseconds. */
    static final long AGE_MS_CLAMP = 600_000L;

    private TsCalculator() {
    }

    /**
     * Computes `ts` for one click. It throws {@link IngestException} with
     * the reason {@code NEGATIVE_AGE_MS} when {@code ageMs} is negative
     * (rule C15).
     */
    public static Instant computeTs(Instant receivedAt, long ageMs) {
        if (ageMs < 0) {
            throw new IngestException(IngestException.Reason.NEGATIVE_AGE_MS,
                    "The ageMs value is negative.");
        }
        long clampedAgeMs = Math.min(ageMs, AGE_MS_CLAMP);
        return receivedAt.minusMillis(clampedAgeMs);
    }
}
