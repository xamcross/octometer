package octometer.kit.core.ingest;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import octometer.kit.core.path.PathPatternMatcher;

/**
 * The settings of the ingest flow (design decision D19; design decision
 * D40, contract rule C42, issue #104). A test sets each field with the
 * constructor. That way, a test needs no change of the process
 * environment.
 *
 * <p>{@code pathPatternMatcher} is the route pattern list of rule C42, as
 * one built {@link PathPatternMatcher}. It is {@code null} when the app
 * gives no list, or an invalid list; rule C42 treats the two cases the
 * same way. {@link IngestPipeline} then stores no {@code path} field for
 * each click.
 */
public record IngestSettings(boolean recordAnonymousClicks, PathPatternMatcher pathPatternMatcher) {

    private static final Logger LOGGER = System.getLogger("octometer.kit.core");

    /** The environment variable of the route pattern list (design decision D40). */
    private static final String PATH_PATTERNS_VARIABLE = "OCTOMETER_PATH_PATTERNS";

    /**
     * Builds a setting with no route pattern list. A caller from before
     * issue #104 still compiles with this constructor.
     */
    public IngestSettings(boolean recordAnonymousClicks) {
        this(recordAnonymousClicks, null);
    }

    /**
     * Reads {@code OCTOMETER_RECORD_ANONYMOUS} and {@code
     * OCTOMETER_PATH_PATTERNS} from the process environment, then builds
     * the setting with {@link #fromValue} and {@link
     * #pathPatternMatcherFromValue}. This method is the one place that
     * reads each variable.
     */
    public static IngestSettings fromEnvironment() {
        boolean recordAnonymousClicks =
                fromValue(System.getenv("OCTOMETER_RECORD_ANONYMOUS")).recordAnonymousClicks();
        PathPatternMatcher pathPatternMatcher = pathPatternMatcherFromValue(System.getenv(PATH_PATTERNS_VARIABLE));
        return new IngestSettings(recordAnonymousClicks, pathPatternMatcher);
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

    /**
     * Turns the raw text of {@code OCTOMETER_PATH_PATTERNS} into one
     * {@link PathPatternMatcher}, or into {@code null} (contract rule
     * C42, design decision D40). The text is a comma-separated list; one
     * entry is one route pattern, in order.
     *
     * <p>This method gives {@code null} for a {@code null} value and for
     * an empty text, and it writes one warning: the app gave no route
     * pattern list, so the kit stores no {@code path} field. It also
     * gives {@code null} when one entry breaks {@link
     * PathPatternMatcher#isValidPattern}; rule C42 treats such a list as
     * no list at all, because a dropped entry would change the match
     * order. That warning names the index of each bad entry, and it
     * never repeats the text of a pattern.
     */
    static PathPatternMatcher pathPatternMatcherFromValue(String rawValue) {
        if (rawValue == null || rawValue.isEmpty()) {
            LOGGER.log(Level.WARNING, "OCTOMETER_PATH_PATTERNS is absent. The kit stores no "
                    + "path field for a click (contract rule C42).");
            return null;
        }
        List<String> patterns = List.of(rawValue.split(",", -1));
        List<Integer> invalidIndices = PathPatternMatcher.findInvalidIndices(patterns);
        if (!invalidIndices.isEmpty()) {
            LOGGER.log(Level.WARNING, "OCTOMETER_PATH_PATTERNS holds an invalid pattern at "
                    + "index " + joinIndices(invalidIndices) + ". The kit treats the whole "
                    + "list as absent and stores no path field (contract rule C42).");
            return null;
        }
        return PathPatternMatcher.of(patterns);
    }

    private static String joinIndices(List<Integer> indices) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < indices.size(); i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(indices.get(i));
        }
        return text.toString();
    }
}
