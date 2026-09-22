package octometer.kit.core.path;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The route pattern matcher of contract rule C42 (issue #104). It matches
 * a client path against an ordered pattern list, and it gives the stored
 * path form. `kit/tracker/src/path-match.ts` holds the same rule for the
 * tracker (issue #106). The shared case file
 * `contract/examples/C42-path-match-cases.json` proves that the two
 * matchers agree on each case (issue #104).
 *
 * <p>This class has no run-time dependency (design decision D17). A
 * caller builds one instance with {@link #of} from a checked pattern
 * list, then calls {@link #match} for each client path. The class does
 * not read `OCTOMETER_PATH_PATTERNS` on its own; {@code IngestSettings}
 * owns that read.
 */
public final class PathPatternMatcher {

    /** The literal fallback of rule C42, for a path with no match. */
    public static final String OTHER = "/other";

    /**
     * The character set of one pattern segment (rule C39). A `*`
     * segment and a `:name` segment pass this same set on their own
     * text, because the set holds the characters `*` and `:`.
     */
    private static final Pattern SEGMENT_CHARACTERS =
            Pattern.compile("(?:[A-Za-z0-9._~!$&'()*+,;=:@-]|%[0-9A-Fa-f]{2})+");

    /** The set of a kept `*` segment (rule C42). A segment outside this set gives {@link #OTHER}. */
    private static final Pattern STAR_SEGMENT =
            Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._~-]|%[0-9A-Fa-f]{2}){0,79}");

    /** The maximum byte count of the stored path, in UTF-8 (rule C42). */
    private static final int MAX_RESULT_BYTES = 150;

    private final List<String[]> patternSegments;

    private PathPatternMatcher(List<String[]> patternSegments) {
        this.patternSegments = patternSegments;
    }

    /**
     * Checks one route pattern against rule C39: a non-empty text that
     * starts with `/`, with each segment inside the character set of
     * rule C39. A {@code null} value is not valid.
     */
    public static boolean isValidPattern(String pattern) {
        if (pattern == null || pattern.isEmpty() || pattern.charAt(0) != '/') {
            return false;
        }
        for (String segment : splitSegments(pattern)) {
            if (segment.isEmpty() || !SEGMENT_CHARACTERS.matcher(segment).matches()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Finds the index of each entry of {@code rawPatterns} that breaks
     * {@link #isValidPattern}, in order. The result is empty when each
     * entry is valid. This method takes no view on an empty {@code
     * rawPatterns} list; a caller must check the list size on its own,
     * because rule C42 treats an absent list and an invalid list as two
     * different cases.
     */
    public static List<Integer> findInvalidIndices(List<String> rawPatterns) {
        List<Integer> invalidIndices = new ArrayList<>();
        for (int index = 0; index < rawPatterns.size(); index++) {
            if (!isValidPattern(rawPatterns.get(index))) {
                invalidIndices.add(index);
            }
        }
        return List.copyOf(invalidIndices);
    }

    /**
     * Builds a matcher from an ordered pattern list. The caller must
     * check {@link #findInvalidIndices} first: rule C42 treats a list
     * with one invalid entry as no list at all, so this method throws
     * for the whole list and not only for the bad entry.
     *
     * @throws IllegalArgumentException when {@code rawPatterns} holds an
     *     entry that breaks {@link #isValidPattern}.
     */
    public static PathPatternMatcher of(List<String> rawPatterns) {
        List<String[]> segments = new ArrayList<>(rawPatterns.size());
        for (String pattern : rawPatterns) {
            if (!isValidPattern(pattern)) {
                throw new IllegalArgumentException(
                        "The route pattern list holds an entry that breaks contract rule C39.");
            }
            segments.add(splitSegments(pattern));
        }
        return new PathPatternMatcher(List.copyOf(segments));
    }

    /**
     * Matches {@code path} against the pattern list, and gives the
     * stored path form of rule C42: the text of the first matching
     * pattern, with each `:name` segment kept literally and each `*`
     * segment replaced by the real segment. It gives {@link #OTHER} for
     * a path with no match, an empty segment, a `.` segment, a `..`
     * segment, a `*` segment with a bad shape, or a result above 150
     * bytes. This method never decodes a `%` escape, and it removes one
     * trailing slash from {@code path} before the match, but never from
     * the root path.
     */
    public String match(String path) {
        String[] pathSegments = splitPathSegments(path);
        if (pathSegments == null) {
            return OTHER;
        }
        for (String[] routeSegments : patternSegments) {
            String result = matchOneRoute(routeSegments, pathSegments);
            if (result != null) {
                return withinByteLimit(result) ? result : OTHER;
            }
        }
        return OTHER;
    }

    /**
     * Tries one route pattern against the path segments. Gives {@code
     * null} when the route does not match: a different segment count,
     * or a literal segment that does not equal the path segment. Gives
     * the stored path, or {@link #OTHER} for a bad `*` segment, when
     * the route matches; a bad `*` segment stops the whole search, and
     * {@link #match} tries no later pattern (rule C42, first match).
     */
    private static String matchOneRoute(String[] routeSegments, String[] pathSegments) {
        if (routeSegments.length != pathSegments.length) {
            return null;
        }
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < routeSegments.length; index++) {
            String routeSegment = routeSegments[index];
            String pathSegment = pathSegments[index];
            String keptSegment;
            if (routeSegment.equals("*")) {
                if (!STAR_SEGMENT.matcher(pathSegment).matches()) {
                    return OTHER;
                }
                keptSegment = pathSegment;
            } else if (routeSegment.length() > 1 && routeSegment.charAt(0) == ':') {
                keptSegment = routeSegment;
            } else if (equalsIgnoreAsciiCase(routeSegment, pathSegment)) {
                keptSegment = routeSegment;
            } else {
                return null;
            }
            result.append('/').append(keptSegment);
        }
        return result.length() == 0 ? "/" : result.toString();
    }

    /**
     * Splits a normalized path into its segments. Gives {@code null}
     * for an empty segment, a `.` segment, or a `..` segment (rule
     * C42).
     */
    private static String[] splitPathSegments(String path) {
        String normalized = removeTrailingSlash(path);
        if (normalized.equals("/")) {
            return new String[0];
        }
        String[] segments = normalized.substring(1).split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return null;
            }
        }
        return segments;
    }

    /** Removes one trailing slash from a path, but never from the root path (rule C42). */
    private static String removeTrailingSlash(String path) {
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    private static String[] splitSegments(String pathOrPattern) {
        return pathOrPattern.equals("/") ? new String[0] : pathOrPattern.substring(1).split("/", -1);
    }

    /** Compares two strings, ignoring the case of an ASCII letter only (rule C42). */
    private static boolean equalsIgnoreAsciiCase(String first, String second) {
        if (first.length() != second.length()) {
            return false;
        }
        for (int index = 0; index < first.length(); index++) {
            if (toAsciiLowerCase(first.charAt(index)) != toAsciiLowerCase(second.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static char toAsciiLowerCase(char c) {
        return c >= 'A' && c <= 'Z' ? (char) (c + 32) : c;
    }

    private static boolean withinByteLimit(String path) {
        return path.getBytes(StandardCharsets.UTF_8).length <= MAX_RESULT_BYTES;
    }
}
