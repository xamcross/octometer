package octometer.kit.spring;

import jakarta.servlet.ServletContext;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import octometer.kit.core.ingest.IngestSettings;
import octometer.kit.core.path.RoutePatternDerivation;
import octometer.kit.core.path.RoutePatternDerivation.RouteTemplate;
import octometer.kit.core.path.RoutePatternDerivation.Segment;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.PatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * The Spring MVC route deriver of design decision D46 (issue #275). The
 * app builds one instance as a plain bean (this class has no Spring
 * Boot dependency, the same rule as `kit/jvm-spring/build.gradle.kts`).
 * Spring calls {@link #afterSingletonsInstantiated} once, after every
 * singleton bean of the app already exists; {@link #handlerMapping}
 * then already holds every mapped route, also a controller bean that
 * this class knows nothing about.
 *
 * <p>This class reads the app's own settings from {@link
 * IngestController#settings()}, and it gives {@link
 * IngestController#updateSettings} a copy with only the {@code path}
 * matcher replaced ({@link IngestSettings#withDerivedPathPatterns});
 * every hand-set field of the app's settings survives (MAJOR 2 of the
 * correction round of issue #275, the same form as the Ktor adapter).
 *
 * <p>A derivation failure (an unknown pattern segment, or another
 * defect) logs one warning with the exception class only, and it
 * leaves the app's own settings in place, with no derived route.
 *
 * <p><strong>The route publication (design decision D47, issue
 * #276).</strong> After a successful derivation, this class calls
 * {@link IngestController#store()}'s {@link
 * octometer.kit.core.store.EventLogStore#publishRoutePatterns} with the
 * derived list. The default of that method does nothing; only a store
 * with a config document, for example {@code MongoEventLogStore} of
 * {@code kit/jvm-mongo}, writes it. A failed derivation publishes
 * nothing.
 */
public final class RoutePatternDerivationInitializer implements SmartInitializingSingleton {

    private static final Logger LOGGER = System.getLogger("octometer.kit.spring");

    /** The character set of one literal pattern segment (contract rule C39). */
    private static final Pattern LITERAL_SEGMENT =
            Pattern.compile("(?:[A-Za-z0-9._~!$&'()*+,;=:@-]|%[0-9A-Fa-f]{2})+");

    private final RequestMappingHandlerMapping handlerMapping;
    private final ServletContext servletContext;
    private final IngestController controller;
    private final String ingestPath;

    /**
     * @param handlerMapping the Spring bean that Spring itself fills
     *   with every {@code @RequestMapping} of the app (for example
     *   {@code requestMappingHandlerMapping}).
     * @param servletContext the servlet context of the app, for its
     *   context path (design decision D46).
     * @param controller the ingest controller that receives the
     *   derived settings.
     * @param ingestPath the effective path of {@code controller}
     *   (already resolved with the servlet context path, when it is
     *   itself relative to it), the same text as the Spring property
     *   {@code octometer.ingest-path}.
     */
    public RoutePatternDerivationInitializer(RequestMappingHandlerMapping handlerMapping,
            ServletContext servletContext, IngestController controller, String ingestPath) {
        this.handlerMapping = Objects.requireNonNull(handlerMapping, "handlerMapping must not be null");
        this.servletContext = Objects.requireNonNull(servletContext, "servletContext must not be null");
        this.controller = Objects.requireNonNull(controller, "controller must not be null");
        this.ingestPath = Objects.requireNonNull(ingestPath, "ingestPath must not be null");
    }

    @Override
    public void afterSingletonsInstantiated() {
        try {
            List<String> derived = RoutePatternDerivation.derive(routeTemplates(), servletContext.getContextPath(),
                    ingestPath);
            // Keep every hand-set field of the app's own settings, and
            // replace only the path matcher (MAJOR 2 of the correction
            // round of issue #275; the same form as the Ktor adapter's
            // IngestSettings.withDerivedPathPatterns).
            controller.updateSettings(controller.settings().withDerivedPathPatterns(derived));
            // The route publication of design decision D47 (issue #276):
            // the derived list only, never the typed addition.
            controller.store().publishRoutePatterns(derived);
        } catch (RuntimeException cause) {
            LOGGER.log(Level.WARNING, "The route derivation of design decision D46 failed. The exception "
                    + "class is {0}. The kit keeps the app's own settings, with no derived route.",
                    cause.getClass().getName());
        }
    }

    private List<RouteTemplate> routeTemplates() {
        List<RouteTemplate> routes = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            String method = methods.isEmpty() || methods.contains(RequestMethod.GET) ? "GET" : null;
            if (method == null) {
                continue;
            }
            for (String rawPattern : rawPatternsOf(info)) {
                routes.add(new RouteTemplate(method, segmentsOf(rawPattern)));
            }
        }
        return routes;
    }

    /**
     * Reads the raw Ant-style patterns of {@code info}: the new {@link
     * PathPattern} condition of {@link PathPatternParser} when Spring
     * built one, or the legacy {@link PatternsRequestCondition}
     * otherwise (Spring 6 supports each form).
     */
    private static Set<String> rawPatternsOf(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() != null) {
            Set<String> patterns = new LinkedHashSet<>();
            for (PathPattern pattern : info.getPathPatternsCondition().getPatterns()) {
                patterns.add(pattern.getPatternString());
            }
            return patterns;
        }
        PatternsRequestCondition legacy = info.getPatternsCondition();
        return legacy == null ? Set.of() : legacy.getPatterns();
    }

    /**
     * Turns one raw Ant-style or {@link PathPattern} pattern text into
     * its segment list (design decision D46).
     *
     * @throws IllegalStateException for a segment form this method does
     *   not know.
     */
    private static List<Segment> segmentsOf(String rawPattern) {
        if (rawPattern.isEmpty() || rawPattern.equals("/")) {
            return List.of();
        }
        String[] rawSegments = rawPattern.substring(1).split("/", -1);
        List<Segment> segments = new ArrayList<>(rawSegments.length);
        for (String rawSegment : rawSegments) {
            segments.add(segmentOf(rawSegment));
        }
        return segments;
    }

    private static Segment segmentOf(String rawSegment) {
        if (rawSegment.equals("*") || rawSegment.equals("**")) {
            return new Segment.Wildcard();
        }
        if (rawSegment.startsWith("{") && rawSegment.endsWith("}")) {
            String inner = rawSegment.substring(1, rawSegment.length() - 1);
            if (inner.startsWith("*")) {
                // A capturing wildcard variable of PathPatternParser, for
                // example "{*path}": it keeps the rest of the path in
                // one variable, the Spring form of a Ktor tail card.
                return new Segment.Wildcard();
            }
            String name = inner.split(":", 2)[0];
            return new Segment.Parameter(name, false);
        }
        if (LITERAL_SEGMENT.matcher(rawSegment).matches()) {
            return new Segment.Literal(rawSegment);
        }
        throw new IllegalStateException(
                "The route derivation of design decision D46 found an unknown pattern segment \"" + rawSegment
                        + "\".");
    }
}
