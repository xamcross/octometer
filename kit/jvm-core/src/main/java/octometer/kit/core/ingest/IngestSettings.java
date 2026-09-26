package octometer.kit.core.ingest;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.regex.Pattern;
import octometer.kit.core.path.PathPatternMatcher;

/**
 * The settings of the ingest flow (design decision D19; design decision
 * D40, contract rule C42, issue #104; design decision D43, issue #117).
 * A test sets each field with the constructor. That way, a test needs no
 * change of the process environment.
 *
 * <p>{@code pathPatternMatcher} is the route pattern list of rule C42, as
 * one built {@link PathPatternMatcher}. It is {@code null} when the app
 * gives no list, or an invalid list; rule C42 treats the two cases the
 * same way. {@link IngestPipeline} then stores no {@code path} field for
 * each click.
 *
 * <p>{@code anonMaxEventsPerDay} and {@code anonEventsPerKeyPerDay} are
 * the two daily caps of design decision D43 and issue #117. A route
 * gives the two values to one {@link AnonymousDailyCap}.
 *
 * <p>{@code anonReqPerMinute}, {@code anonEventsPerMinute}, and {@code
 * anonSessionsPerMinute} are the three per-minute limits of design
 * decision D43 and issue #116. A route gives the three values to one
 * {@link AnonymousMinuteLimiter}.
 */
public record IngestSettings(boolean recordAnonymousClicks, PathPatternMatcher pathPatternMatcher,
        long anonMaxEventsPerDay, long anonEventsPerKeyPerDay, long anonReqPerMinute, long anonEventsPerMinute,
        long anonSessionsPerMinute) {

    private static final Logger LOGGER = System.getLogger("octometer.kit.core");

    /** The environment variable of the route pattern list (design decision D40). */
    private static final String PATH_PATTERNS_VARIABLE = "OCTOMETER_PATH_PATTERNS";

    /** The environment variable of the global daily anonymous cap (design decision D43). */
    private static final String ANON_MAX_EVENTS_PER_DAY_VARIABLE = "OCTOMETER_MAX_ANON_EVENTS_PER_DAY";

    /** The environment variable of the daily anonymous cap of one key (design decision D43). */
    private static final String ANON_EVENTS_PER_KEY_PER_DAY_VARIABLE = "OCTOMETER_ANON_EVENTS_PER_KEY_PER_DAY";

    /** The environment variable of the per-minute request limit (design decision D43, issue #116). */
    private static final String ANON_REQ_PER_MIN_VARIABLE = "OCTOMETER_ANON_REQ_PER_MIN";

    /** The environment variable of the per-minute click entry limit (design decision D43, issue #116). */
    private static final String ANON_EVENTS_PER_MIN_VARIABLE = "OCTOMETER_ANON_EVENTS_PER_MIN";

    /** The environment variable of the per-minute session-start limit (design decision D43, issue #116). */
    private static final String ANON_SESSIONS_PER_MIN_VARIABLE = "OCTOMETER_ANON_SESSIONS_PER_MIN";

    /** The default of {@value #ANON_MAX_EVENTS_PER_DAY_VARIABLE} (design decision D43). */
    public static final long DEFAULT_ANON_MAX_EVENTS_PER_DAY = 20_000;

    /** The default of {@value #ANON_EVENTS_PER_KEY_PER_DAY_VARIABLE} (design decision D43). */
    public static final long DEFAULT_ANON_EVENTS_PER_KEY_PER_DAY = 2_000;

    /** The default of {@value #ANON_REQ_PER_MIN_VARIABLE} (design decision D43, issue #116). */
    public static final long DEFAULT_ANON_REQ_PER_MIN = 300;

    /** The default of {@value #ANON_EVENTS_PER_MIN_VARIABLE} (design decision D43, issue #116). */
    public static final long DEFAULT_ANON_EVENTS_PER_MIN = 900;

    /** The default of {@value #ANON_SESSIONS_PER_MIN_VARIABLE} (design decision D43, issue #116). */
    public static final long DEFAULT_ANON_SESSIONS_PER_MIN = 120;

    /** Only an ASCII digit sets a daily cap or a per-minute limit. A Unicode digit does not. */
    private static final Pattern ASCII_DIGITS = Pattern.compile("[0-9]+");

    /**
     * Builds a setting with no route pattern list, the default daily
     * caps, and the default per-minute limits. A caller from before
     * issue #104 still compiles with this constructor.
     */
    public IngestSettings(boolean recordAnonymousClicks) {
        this(recordAnonymousClicks, null, DEFAULT_ANON_MAX_EVENTS_PER_DAY, DEFAULT_ANON_EVENTS_PER_KEY_PER_DAY);
    }

    /**
     * Builds a setting with the default daily caps and the default
     * per-minute limits. A caller from before issue #117 still compiles
     * with this constructor.
     */
    public IngestSettings(boolean recordAnonymousClicks, PathPatternMatcher pathPatternMatcher) {
        this(recordAnonymousClicks, pathPatternMatcher, DEFAULT_ANON_MAX_EVENTS_PER_DAY,
                DEFAULT_ANON_EVENTS_PER_KEY_PER_DAY);
    }

    /**
     * Builds a setting with the default per-minute limits. A caller from
     * before issue #116 still compiles with this constructor.
     */
    public IngestSettings(boolean recordAnonymousClicks, PathPatternMatcher pathPatternMatcher,
            long anonMaxEventsPerDay, long anonEventsPerKeyPerDay) {
        this(recordAnonymousClicks, pathPatternMatcher, anonMaxEventsPerDay, anonEventsPerKeyPerDay,
                DEFAULT_ANON_REQ_PER_MIN, DEFAULT_ANON_EVENTS_PER_MIN, DEFAULT_ANON_SESSIONS_PER_MIN);
    }

    /**
     * Reads {@code OCTOMETER_RECORD_ANONYMOUS}, {@code
     * OCTOMETER_PATH_PATTERNS}, {@code OCTOMETER_MAX_ANON_EVENTS_PER_DAY},
     * {@code OCTOMETER_ANON_EVENTS_PER_KEY_PER_DAY}, {@code
     * OCTOMETER_ANON_REQ_PER_MIN}, {@code OCTOMETER_ANON_EVENTS_PER_MIN},
     * and {@code OCTOMETER_ANON_SESSIONS_PER_MIN} from the process
     * environment, then builds the setting. This method is the one place
     * that reads each variable.
     */
    public static IngestSettings fromEnvironment() {
        boolean recordAnonymousClicks =
                fromValue(System.getenv("OCTOMETER_RECORD_ANONYMOUS")).recordAnonymousClicks();
        PathPatternMatcher pathPatternMatcher = pathPatternMatcherFromValue(System.getenv(PATH_PATTERNS_VARIABLE));
        long anonMaxEventsPerDay = positiveWholeNumberFromValue(System.getenv(ANON_MAX_EVENTS_PER_DAY_VARIABLE),
                ANON_MAX_EVENTS_PER_DAY_VARIABLE, DEFAULT_ANON_MAX_EVENTS_PER_DAY);
        long anonEventsPerKeyPerDay = positiveWholeNumberFromValue(
                System.getenv(ANON_EVENTS_PER_KEY_PER_DAY_VARIABLE), ANON_EVENTS_PER_KEY_PER_DAY_VARIABLE,
                DEFAULT_ANON_EVENTS_PER_KEY_PER_DAY);
        long anonReqPerMinute = positiveWholeNumberFromValue(System.getenv(ANON_REQ_PER_MIN_VARIABLE),
                ANON_REQ_PER_MIN_VARIABLE, DEFAULT_ANON_REQ_PER_MIN);
        long anonEventsPerMinute = positiveWholeNumberFromValue(System.getenv(ANON_EVENTS_PER_MIN_VARIABLE),
                ANON_EVENTS_PER_MIN_VARIABLE, DEFAULT_ANON_EVENTS_PER_MIN);
        long anonSessionsPerMinute = positiveWholeNumberFromValue(System.getenv(ANON_SESSIONS_PER_MIN_VARIABLE),
                ANON_SESSIONS_PER_MIN_VARIABLE, DEFAULT_ANON_SESSIONS_PER_MIN);
        return new IngestSettings(recordAnonymousClicks, pathPatternMatcher, anonMaxEventsPerDay,
                anonEventsPerKeyPerDay, anonReqPerMinute, anonEventsPerMinute, anonSessionsPerMinute);
    }

    /**
     * Turns the raw text of one daily-cap variable into its value
     * (design decision D43, issue #117, the form of {@code
     * MongoEventLogStore.maxEventsFromValue}). A {@code null} value
     * gives {@code defaultValue}, with no warning.
     *
     * <p>A value of zero, a negative value, or a value with a character
     * that is not an ASCII digit, stops the app start: this method
     * throws {@link IllegalStateException}, with a message that names
     * {@code variableName} and never repeats the raw value.
     */
    static long positiveWholeNumberFromValue(String rawValue, String variableName, long defaultValue) {
        if (rawValue == null) {
            return defaultValue;
        }
        String trimmed = rawValue.trim();
        if (ASCII_DIGITS.matcher(trimmed).matches()) {
            try {
                long value = Long.parseLong(trimmed);
                if (value > 0) {
                    return value;
                }
            } catch (NumberFormatException cause) {
                // A text of only ASCII digits can still overflow a long.
                // The error below covers this case too.
            }
        }
        throw new IllegalStateException(variableName + " must hold a positive whole number of ASCII "
                + "digits. The app start stops, because a wrong daily cap can let an anonymous flood "
                + "reach the store.");
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
     * C42, design decision D40).
     *
     * <p>A run of whitespace separates each entry: a space, a tab, or a
     * line break. One entry is one route pattern, in order. This method
     * trims the leading and the trailing whitespace of the whole text,
     * and it trims nothing else; a comma inside an entry stays a part of
     * that one pattern.
     *
     * <p>This method gives {@code null} for a {@code null} value, for an
     * empty text, and for a text of whitespace only. It writes one
     * warning: the app gave no route pattern list, so the kit stores no
     * {@code path} field.
     *
     * <p>This method also gives {@code null} when one entry breaks
     * {@link PathPatternMatcher#isValidPattern}. Rule C42 treats such a
     * list as no list at all, because a dropped entry would change the
     * match order. That warning names the index of each bad entry, and
     * it never repeats the text of a pattern.
     */
    static PathPatternMatcher pathPatternMatcherFromValue(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            LOGGER.log(Level.WARNING, "OCTOMETER_PATH_PATTERNS is absent. The kit stores no "
                    + "path field for a click (contract rule C42).");
            return null;
        }
        List<String> patterns = List.of(rawValue.trim().split("\\s+"));
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
