package octometer.kit.core.ingest;

/**
 * The result of one check of {@link IngestRateLimiter#check} (design
 * decision D20, issue #33). An adapter maps {@link #LIMITED} to status
 * 429 (contract rule C19).
 */
public enum RateLimitResult {

    /** The request stays inside the window limit. */
    ALLOWED,

    /** The request breaks the window limit, or the key map is full. */
    LIMITED
}
