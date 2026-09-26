package octometer.kit.core.ingest;

/**
 * The result of one check of {@link IngestRequestProcessor} (issue #69).
 * An adapter maps {@link #statusCode()} to its own status type, and writes
 * {@link #body()} only when it is not {@code null}. Every result of this
 * kit today holds a {@code null} body; the field stays here for a future
 * result with a fixed body.
 */
public record IngestResult(int statusCode, String body) {

    /** Builds a result with no body. */
    public IngestResult(int statusCode) {
        this(statusCode, null);
    }
}
