package octometer.kit.core.ingest;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * The settings of the ingest flow (design decision D19). A test sets
 * {@code recordAnonymousClicks} with the constructor. That way, a test
 * needs no change of the process environment.
 */
public record IngestSettings(boolean recordAnonymousClicks) {

    private static final Logger LOGGER = System.getLogger("octometer.kit.core");

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
     *
     * <p>This method writes one warning through {@link System.Logger}
     * when the value is neither {@code null}, nor the exact text
     * {@code true}, nor the exact text {@code false}. {@code
     * System.Logger} is a part of `java.base`, so this rule adds no
     * dependency. The warning names the variable and the rule; it never
     * repeats the value.
     */
    static IngestSettings fromValue(String rawValue) {
        if (rawValue != null && !"true".equals(rawValue) && !"false".equals(rawValue)) {
            LOGGER.log(Level.WARNING, "OCTOMETER_RECORD_ANONYMOUS holds a value that is "
                    + "neither the exact text true nor the exact text false (design decision "
                    + "D19). Only the exact text true turns the flag on; the flag stays off.");
        }
        return new IngestSettings("true".equals(rawValue));
    }
}
