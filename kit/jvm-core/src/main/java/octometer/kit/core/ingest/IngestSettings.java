package octometer.kit.core.ingest;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 *
 * <p>{@code acceptBodyUserId} is the hosted collector's own exception of
 * issue #300 (design decision D54, the brief review of 2026-10-01, point
 * 23): the default is {@code false}, so the kit ingest route keeps rule
 * C16 and rule C32 with no change. The collector route sets it to
 * {@code true}, so the top-level {@code userId} field of the body
 * reaches the stored event, through {@link IngestPipeline#processBatch}.
 */
public record IngestSettings(boolean recordAnonymousClicks, PathPatternMatcher pathPatternMatcher,
        long anonMaxEventsPerDay, long anonEventsPerKeyPerDay, long anonReqPerMinute, long anonEventsPerMinute,
        long anonSessionsPerMinute, boolean acceptBodyUserId) {

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

    /** The bound of the route pattern list of design decision D46, issue #275. */
    private static final int MAX_PATH_PATTERN_COUNT = 200;

    /**
     * Builds a setting with no route pattern list, the default daily
     * caps, and the default per-minute limits. A caller from before
     * issue #104 still compiles with this constructor.
     */
    public IngestSettings(boolean recordAnonymousClicks) {
        this(recordAnonymousClicks, null, DEFAULT_ANON_MAX_EVENTS_PER_DAY, DEFAULT_ANON_EVENTS_PER_KEY_PER_DAY);
    }

    /**
     * Builds a setting with no route pattern list, the default daily
     * caps, and the default per-minute limits, and {@code
     * acceptBodyUserId} set (issue #300). The collector route uses this
     * constructor with {@code true}.
     */
    public IngestSettings(boolean recordAnonymousClicks, boolean acceptBodyUserId) {
        this(recordAnonymousClicks, null, DEFAULT_ANON_MAX_EVENTS_PER_DAY, DEFAULT_ANON_EVENTS_PER_KEY_PER_DAY,
                DEFAULT_ANON_REQ_PER_MIN, DEFAULT_ANON_EVENTS_PER_MIN, DEFAULT_ANON_SESSIONS_PER_MIN,
                acceptBodyUserId);
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
     * Builds a setting with the default daily caps, the default
     * per-minute limits, and {@code acceptBodyUserId} set (issue #300).
     * The collector route uses this constructor with {@code true}.
     */
    public IngestSettings(boolean recordAnonymousClicks, PathPatternMatcher pathPatternMatcher,
            boolean acceptBodyUserId) {
        this(recordAnonymousClicks, pathPatternMatcher, DEFAULT_ANON_MAX_EVENTS_PER_DAY,
                DEFAULT_ANON_EVENTS_PER_KEY_PER_DAY, DEFAULT_ANON_REQ_PER_MIN, DEFAULT_ANON_EVENTS_PER_MIN,
                DEFAULT_ANON_SESSIONS_PER_MIN, acceptBodyUserId);
    }

    /**
     * Builds a setting with the default per-minute limits. A caller from
     * before issue #116 still compiles with this constructor.
     */
    public IngestSettings(boolean recordAnonymousClicks, PathPatternMatcher pathPatternMatcher,
            long anonMaxEventsPerDay, long anonEventsPerKeyPerDay) {
        this(recordAnonymousClicks, pathPatternMatcher, anonMaxEventsPerDay, anonEventsPerKeyPerDay,
                DEFAULT_ANON_REQ_PER_MIN, DEFAULT_ANON_EVENTS_PER_MIN, DEFAULT_ANON_SESSIONS_PER_MIN, false);
    }

    /**
     * Builds a setting with the default per-minute limits, and {@code
     * acceptBodyUserId} set (issue #300).
     */
    public IngestSettings(boolean recordAnonymousClicks, PathPatternMatcher pathPatternMatcher,
            long anonMaxEventsPerDay, long anonEventsPerKeyPerDay, boolean acceptBodyUserId) {
        this(recordAnonymousClicks, pathPatternMatcher, anonMaxEventsPerDay, anonEventsPerKeyPerDay,
                DEFAULT_ANON_REQ_PER_MIN, DEFAULT_ANON_EVENTS_PER_MIN, DEFAULT_ANON_SESSIONS_PER_MIN,
                acceptBodyUserId);
    }

    /**
     * Builds a setting with every field named (issue #116). A caller
     * from before issue #300 still compiles with this constructor;
     * {@code acceptBodyUserId} defaults to {@code false}.
     */
    public IngestSettings(boolean recordAnonymousClicks, PathPatternMatcher pathPatternMatcher,
            long anonMaxEventsPerDay, long anonEventsPerKeyPerDay, long anonReqPerMinute, long anonEventsPerMinute,
            long anonSessionsPerMinute) {
        this(recordAnonymousClicks, pathPatternMatcher, anonMaxEventsPerDay, anonEventsPerKeyPerDay,
                anonReqPerMinute, anonEventsPerMinute, anonSessionsPerMinute, false);
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
     * Builds the setting from {@code derivedPatterns} (design decision
     * D46, issue #275) and each other environment variable of {@link
     * #fromEnvironment()}. A Ktor or a Spring adapter calls this method
     * once its framework derives its own route pattern list; {@code
     * derivedPatterns} is that list, already sorted and with no
     * duplicate ({@code octometer.kit.core.path.RoutePatternDerivation}
     * of `kit/jvm-core`).
     *
     * <p>{@link #PATH_PATTERNS_VARIABLE} stays optional. Its entries
     * stand after {@code derivedPatterns}, so a typed pattern never
     * hides a derived one. An invalid entry of {@value
     * #PATH_PATTERNS_VARIABLE} makes the whole combined list count as
     * no list at all (contract rule C42), the same rule as {@link
     * #fromEnvironment()}.
     *
     * <p>An app with no GET route and no {@value #PATH_PATTERNS_VARIABLE}
     * gives an empty combined list; this method then keeps today's
     * behaviour, a {@code null} matcher with one warning. A combined
     * list above {@value #MAX_PATH_PATTERN_COUNT} entries keeps only
     * its first {@value #MAX_PATH_PATTERN_COUNT} entries, with one
     * warning. This method writes one INFO line with the final stored
     * count.
     */
    public static IngestSettings fromEnvironment(List<String> derivedPatterns) {
        boolean recordAnonymousClicks =
                fromValue(System.getenv("OCTOMETER_RECORD_ANONYMOUS")).recordAnonymousClicks();
        PathPatternMatcher pathPatternMatcher =
                combinedPathPatternMatcher(derivedPatterns, System.getenv(PATH_PATTERNS_VARIABLE));
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
     * Builds a copy of this setting, with {@code pathPatternMatcher} in
     * place of the matcher of this instance, and every other field
     * unchanged.
     */
    public IngestSettings withPathPatternMatcher(PathPatternMatcher pathPatternMatcher) {
        return new IngestSettings(recordAnonymousClicks(), pathPatternMatcher, anonMaxEventsPerDay(),
                anonEventsPerKeyPerDay(), anonReqPerMinute(), anonEventsPerMinute(), anonSessionsPerMinute(),
                acceptBodyUserId());
    }

    /**
     * Builds a copy of this setting, with {@code acceptBodyUserId} in
     * place of the flag of this instance, and every other field
     * unchanged (issue #300). The collector route calls this method with
     * {@code true}.
     */
    public IngestSettings withAcceptBodyUserId(boolean acceptBodyUserId) {
        return new IngestSettings(recordAnonymousClicks(), pathPatternMatcher(), anonMaxEventsPerDay(),
                anonEventsPerKeyPerDay(), anonReqPerMinute(), anonEventsPerMinute(), anonSessionsPerMinute(),
                acceptBodyUserId);
    }

    /**
     * Builds a copy of this setting, with the combined matcher of
     * {@code derivedPatterns} and {@value #PATH_PATTERNS_VARIABLE}
     * (design decision D46, issue #275; see {@link
     * #combinedPathPatternMatcher}), and every other field unchanged. A
     * Ktor adapter calls this method on the settings that the app gave
     * to `octometerIngestRoute`, once its framework derives its own
     * route pattern list, so a field that the app set by hand (for
     * example {@code recordAnonymousClicks}) survives the derivation.
     */
    public IngestSettings withDerivedPathPatterns(List<String> derivedPatterns) {
        return withPathPatternMatcher(combinedPathPatternMatcher(derivedPatterns, System.getenv(PATH_PATTERNS_VARIABLE)));
    }

    /**
     * Builds the combined matcher of {@link #fromEnvironment(List)}:
     * {@code derivedPatterns}, then each entry of {@code rawTypedValue}
     * (the raw text of {@value #PATH_PATTERNS_VARIABLE}), with no
     * duplicate, the bound of {@value #MAX_PATH_PATTERN_COUNT}, and the
     * one INFO line of the final count.
     */
    static PathPatternMatcher combinedPathPatternMatcher(List<String> derivedPatterns, String rawTypedValue) {
        List<String> typedPatterns = rawTypedValue == null || rawTypedValue.isBlank()
                ? List.of()
                : List.of(rawTypedValue.trim().split("\\s+"));
        List<Integer> invalidIndices = PathPatternMatcher.findInvalidIndices(typedPatterns);
        if (!invalidIndices.isEmpty()) {
            LOGGER.log(Level.WARNING, "OCTOMETER_PATH_PATTERNS holds an invalid pattern at "
                    + "index " + joinIndices(invalidIndices) + ". The kit treats the whole "
                    + "list as absent and stores no path field (contract rule C42).");
            return null;
        }
        List<String> combined = new ArrayList<>(derivedPatterns.size() + typedPatterns.size());
        combined.addAll(derivedPatterns);
        combined.addAll(typedPatterns);
        List<String> deduplicated = keepFirstOccurrence(combined);
        if (deduplicated.isEmpty()) {
            LOGGER.log(Level.WARNING, "The app derives no GET route, and OCTOMETER_PATH_PATTERNS "
                    + "is absent. The kit stores no path field for a click (contract rule C42, "
                    + "design decision D46).");
            return null;
        }
        List<String> bounded = deduplicated;
        if (deduplicated.size() > MAX_PATH_PATTERN_COUNT) {
            bounded = deduplicated.subList(0, MAX_PATH_PATTERN_COUNT);
            LOGGER.log(Level.WARNING, "The route pattern list holds " + deduplicated.size()
                    + " entries, above the bound of " + MAX_PATH_PATTERN_COUNT + " (design "
                    + "decision D46). The kit keeps the first " + MAX_PATH_PATTERN_COUNT + " entries.");
        }
        LOGGER.log(Level.INFO, "The kit stores the path field with " + bounded.size()
                + " route patterns (design decision D46).");
        return PathPatternMatcher.of(bounded);
    }

    /** Keeps the first occurrence of each entry of {@code patterns}, in order. */
    private static List<String> keepFirstOccurrence(List<String> patterns) {
        List<String> result = new ArrayList<>(patterns.size());
        Set<String> seen = new HashSet<>();
        for (String pattern : patterns) {
            if (seen.add(pattern)) {
                result.add(pattern);
            }
        }
        return result;
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
