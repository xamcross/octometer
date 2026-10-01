package octometer.kit.core.path;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * The default route pattern of design decision D40 and design decision
 * D50 (issue #297). The hosted collector of {@code monitor/backend}
 * applies this rule to a client path when the app gives no typed route
 * pattern list, or when {@link PathPatternMatcher#match} answers
 * {@link PathPatternMatcher#OTHER} for that path.
 *
 * <p>This class turns one path segment into the literal {@code :id} when
 * the segment is a whole number, a UUID, or a hex string of 8 characters
 * or more (each letter case). Each other segment stays as it is. The
 * query string never counts: a caller must give this method a path with
 * no query string.
 *
 * <p>This class has no run-time dependency (design decision D17), the
 * same as {@link PathPatternMatcher}.
 */
public final class DefaultRoutePattern {

    /** A segment of ASCII digits only. */
    private static final Pattern WHOLE_NUMBER = Pattern.compile("^[0-9]+$");

    /** A segment with the shape of a UUID, each letter case. */
    private static final Pattern UUID_SEGMENT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /** A segment of 8 or more hex characters, each letter case. */
    private static final Pattern HEX_SEGMENT = Pattern.compile("^[0-9a-fA-F]{8,}$");

    /** The maximum byte count of the result, the same bound as {@link PathPatternMatcher} (rule C42). */
    private static final int MAX_RESULT_BYTES = 150;

    private DefaultRoutePattern() {
    }

    /**
     * Applies the default rule to {@code path}. Each segment that
     * matches a whole number, a UUID, or a hex string of 8 characters or
     * more becomes {@code :id}. Each other segment stays as it is.
     *
     * <p>This method gives {@link PathPatternMatcher#OTHER} for a
     * {@code null} value, an empty value, a value with no leading
     * slash, and a result above 150 bytes in UTF-8.
     */
    public static String apply(String path) {
        if (path == null || path.isEmpty() || path.charAt(0) != '/') {
            return PathPatternMatcher.OTHER;
        }
        int queryStart = path.indexOf('?');
        String withoutQuery = queryStart >= 0 ? path.substring(0, queryStart) : path;
        if (withoutQuery.equals("/")) {
            return "/";
        }
        String trimmed = withoutQuery.length() > 1 && withoutQuery.endsWith("/")
                ? withoutQuery.substring(1, withoutQuery.length() - 1)
                : withoutQuery.substring(1);
        if (trimmed.isEmpty()) {
            return "/";
        }
        String[] segments = trimmed.split("/", -1);
        StringBuilder result = new StringBuilder();
        for (String segment : segments) {
            result.append('/').append(mappedSegment(segment));
        }
        String resultText = result.toString();
        return withinByteLimit(resultText) ? resultText : PathPatternMatcher.OTHER;
    }

    private static String mappedSegment(String segment) {
        if (WHOLE_NUMBER.matcher(segment).matches()
                || UUID_SEGMENT.matcher(segment).matches()
                || HEX_SEGMENT.matcher(segment).matches()) {
            return ":id";
        }
        return segment;
    }

    private static boolean withinByteLimit(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length <= MAX_RESULT_BYTES;
    }
}
