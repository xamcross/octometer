package octometer.kit.core.ingest;

/**
 * The settings of the ingest flow (design decision D19). A test sets
 * {@code recordAnonymousClicks} with the constructor. That way, a test
 * needs no change of the process environment.
 */
public record IngestSettings(boolean recordAnonymousClicks) {

    /**
     * Reads {@code OCTOMETER_RECORD_ANONYMOUS} from the process
     * environment, then builds the setting with {@link #fromValue}. This
     * method is the one place that reads the flag.
     */
    public static IngestSettings fromEnvironment() {
        return fromValue(System.getenv("OCTOMETER_RECORD_ANONYMOUS"));
    }

    /**
     * Turns the raw text of {@code OCTOMETER_RECORD_ANONYMOUS} into one
     * setting (design decision D19). Only the exact text {@code true}
     * turns the flag on. A {@code null} value, an empty text, a
     * different case such as {@code TRUE}, and a text with a leading or
     * a trailing space all give the flag off. The default is off.
     */
    static IngestSettings fromValue(String rawValue) {
        return new IngestSettings("true".equals(rawValue));
    }
}
