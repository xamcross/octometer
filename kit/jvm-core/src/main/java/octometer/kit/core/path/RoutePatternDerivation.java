package octometer.kit.core.path;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The route pattern deriver of design decision D46 (issue #275). It
 * turns the GET route templates of an app framework into the ordered
 * pattern list of contract rule C42. An adapter of `kit/jvm-ktor` or
 * `kit/jvm-spring` walks the route tree of its own framework, builds one
 * {@link RouteTemplate} for each route, and calls {@link #derive}. That
 * adapter then gives the result to {@link IngestSettings#fromEnvironment(List)}
 * of `kit/jvm-core`, together with the optional entries of {@code
 * OCTOMETER_PATH_PATTERNS}.
 *
 * <p>This class has no framework dependency (design decision D17). It
 * reads no environment variable of its own; a caller passes {@code
 * excludePrefixes} in, read from {@code OCTOMETER_PATH_EXCLUDE} through
 * {@link #excludePrefixesFromEnvironment}.
 */
public final class RoutePatternDerivation {

    /** The environment variable of the route exclude prefixes (design decision D46). */
    public static final String PATH_EXCLUDE_VARIABLE = "OCTOMETER_PATH_EXCLUDE";

    private RoutePatternDerivation() {
    }

    /**
     * One segment of a route template, before the C42 conversion. {@link
     * Literal} keeps its own text. {@link Parameter} becomes {@code
     * :name}; {@link Parameter#optional()} true also gives the route
     * with no such segment (design decision D46). {@link Wildcard}
     * becomes {@code *} (a Ktor tail card, a Ktor regex segment, or a
     * Spring wildcard segment).
     */
    public sealed interface Segment {

        record Literal(String text) implements Segment {
        }

        record Parameter(String name, boolean optional) implements Segment {
        }

        record Wildcard() implements Segment {
        }
    }

    /**
     * One route of the app framework: its HTTP method (for example
     * {@code "GET"}) and its ordered segment list. An empty segment
     * list is the root path.
     */
    public record RouteTemplate(String method, List<Segment> segments) {

        public RouteTemplate(String method, List<Segment> segments) {
            this.method = method;
            this.segments = List.copyOf(segments);
        }
    }

    /**
     * Reads {@value #PATH_EXCLUDE_VARIABLE} from the process environment:
     * a run of whitespace separates each prefix. An absent or a blank
     * value gives an empty list, with no warning; the option is optional
     * on its own.
     */
    public static List<String> excludePrefixesFromEnvironment() {
        String rawValue = System.getenv(PATH_EXCLUDE_VARIABLE);
        if (rawValue == null || rawValue.isBlank()) {
            return List.of();
        }
        return List.of(rawValue.trim().split("\\s+"));
    }

    /**
     * Derives the C42 pattern list of {@code routes}, with the exclude
     * prefixes of {@link #excludePrefixesFromEnvironment}.
     *
     * @see #derive(List, String, String, List)
     */
    public static List<String> derive(List<RouteTemplate> routes, String rootPath, String ingestPath) {
        return derive(routes, rootPath, ingestPath, excludePrefixesFromEnvironment());
    }

    /**
     * Derives the ordered C42 pattern list of the GET routes of {@code
     * routes} (design decision D46).
     *
     * <p>This method keeps a route only when its method is the exact
     * text {@code "GET"}. It skips a route whose derived pattern equals
     * {@code ingestPath} (ASCII letter case ignored), and a route whose
     * derived pattern starts with an entry of {@code excludePrefixes}.
     * It expands each {@link Segment.Parameter} with {@link
     * Segment.Parameter#optional()} true into two routes: one with a
     * {@code :name} segment, one with no such segment. It sorts the
     * result at each depth: a literal segment first, then a {@code
     * :name} segment, then a {@code *} segment, then by the text of the
     * segment; it removes each duplicate.
     *
     * <p>This method gives the derived list only. It appends no entry
     * of {@code OCTOMETER_PATH_PATTERNS}, and it applies no bound of
     * 200 entries; {@link IngestSettings#fromEnvironment(List)} of
     * `kit/jvm-core` does both, because it also owns the typed list.
     *
     * @param routes every route of the app framework, of every method.
     * @param rootPath the root path of the app (a Ktor {@code rootPath},
     *   a Spring servlet context path), or an empty text with no root
     *   path. This method prefixes every derived pattern with it.
     * @param ingestPath the effective path of the kit's own ingest
     *   route (already prefixed with {@code rootPath} by the caller,
     *   when the caller's ingest path is itself relative to it).
     * @param excludePrefixes each prefix that skips a route, in the same
     *   form as a derived pattern (starts with {@code /}).
     */
    public static List<String> derive(List<RouteTemplate> routes, String rootPath, String ingestPath,
            List<String> excludePrefixes) {
        String normalizedRoot = normalizeRoot(rootPath);
        Set<String> candidates = new LinkedHashSet<>();
        for (RouteTemplate route : routes) {
            if (!"GET".equals(route.method())) {
                continue;
            }
            for (List<Segment> variant : expandOptionalSegments(route.segments())) {
                String pattern = withRootPrefix(normalizedRoot, toTemplatePath(variant));
                if (equalsIgnoreAsciiCase(pattern, ingestPath) || startsWithAnyPrefix(pattern, excludePrefixes)) {
                    continue;
                }
                candidates.add(pattern);
            }
        }
        List<String> sorted = new ArrayList<>(candidates);
        sorted.sort(RoutePatternDerivation::compareByDepthThenText);
        return List.copyOf(sorted);
    }

    /**
     * Expands each optional {@link Segment.Parameter} of {@code
     * segments} into the two routes of design decision D46: one with
     * the segment as {@code :name}, one with no such segment. A route
     * with two optional segments gives four variants; a route with no
     * optional segment gives one variant, {@code segments} itself.
     */
    private static List<List<Segment>> expandOptionalSegments(List<Segment> segments) {
        List<List<Segment>> variants = new ArrayList<>();
        variants.add(new ArrayList<>());
        for (Segment segment : segments) {
            List<List<Segment>> nextVariants = new ArrayList<>(variants.size() * 2);
            for (List<Segment> variant : variants) {
                if (segment instanceof Segment.Parameter parameter && parameter.optional()) {
                    nextVariants.add(new ArrayList<>(variant));
                    List<Segment> withSegment = new ArrayList<>(variant);
                    withSegment.add(new Segment.Parameter(parameter.name(), false));
                    nextVariants.add(withSegment);
                } else {
                    List<Segment> withSegment = new ArrayList<>(variant);
                    withSegment.add(segment);
                    nextVariants.add(withSegment);
                }
            }
            variants = nextVariants;
        }
        return variants;
    }

    /** Turns one segment variant into its C42 template path, for example {@code /items/:id}. */
    private static String toTemplatePath(List<Segment> segments) {
        if (segments.isEmpty()) {
            return "/";
        }
        StringBuilder text = new StringBuilder();
        for (Segment segment : segments) {
            text.append('/').append(toToken(segment));
        }
        return text.toString();
    }

    private static String toToken(Segment segment) {
        if (segment instanceof Segment.Literal literal) {
            return literal.text();
        }
        if (segment instanceof Segment.Parameter parameter) {
            return ":" + parameter.name();
        }
        return "*";
    }

    /** Removes a trailing slash and requires a leading slash, or gives an empty text for the root path. */
    private static String normalizeRoot(String rootPath) {
        if (rootPath == null || rootPath.isEmpty() || rootPath.equals("/")) {
            return "";
        }
        String trimmed = rootPath.endsWith("/") ? rootPath.substring(0, rootPath.length() - 1) : rootPath;
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private static String withRootPrefix(String normalizedRoot, String templatePath) {
        if (normalizedRoot.isEmpty()) {
            return templatePath;
        }
        return templatePath.equals("/") ? normalizedRoot : normalizedRoot + templatePath;
    }

    private static boolean startsWithAnyPrefix(String pattern, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (pattern.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean equalsIgnoreAsciiCase(String first, String second) {
        if (first == null || second == null || first.length() != second.length()) {
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

    /** The sort order of design decision D46: a literal segment, then {@code :name}, then {@code *}, then by text. */
    private static int compareByDepthThenText(String first, String second) {
        String[] firstSegments = splitSegments(first);
        String[] secondSegments = splitSegments(second);
        int commonLength = Math.min(firstSegments.length, secondSegments.length);
        for (int index = 0; index < commonLength; index++) {
            int rankCompare = Integer.compare(rankOf(firstSegments[index]), rankOf(secondSegments[index]));
            if (rankCompare != 0) {
                return rankCompare;
            }
            int textCompare = firstSegments[index].compareTo(secondSegments[index]);
            if (textCompare != 0) {
                return textCompare;
            }
        }
        return Integer.compare(firstSegments.length, secondSegments.length);
    }

    private static int rankOf(String segment) {
        if (segment.equals("*")) {
            return 2;
        }
        return segment.startsWith(":") ? 1 : 0;
    }

    private static String[] splitSegments(String pattern) {
        return pattern.equals("/") ? new String[0] : pattern.substring(1).split("/", -1);
    }
}
