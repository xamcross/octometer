package octometer.kit.core.ingest;

/**
 * The settings of the ingest flow (design decision D19). A test sets
 * {@code recordAnonymousClicks} with the constructor. That way, a test
 * needs no change of the process environment.
 */
public record IngestSettings(boolean recordAnonymousClicks) {

    /**
     * Reads {@code OCTOMETER_RECORD_ANONYMOUS} from the process
     * environment. This method is the one place that reads the flag. The
     * default is off.
     */
    public static IngestSettings fromEnvironment() {
        return new IngestSettings("true".equalsIgnoreCase(System.getenv("OCTOMETER_RECORD_ANONYMOUS")));
    }
}
