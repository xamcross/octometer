package octometer.kit.ktor

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentOptionalParameterRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.PathSegmentRegexRouteSelector
import io.ktor.server.routing.PathSegmentTailcardRouteSelector
import io.ktor.server.routing.PathSegmentWildcardRouteSelector
import io.ktor.server.routing.RootRouteSelector
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.TrailingSlashRouteSelector
import io.ktor.server.routing.routingRoot
import java.lang.System.Logger
import java.lang.System.Logger.Level
import octometer.kit.core.ingest.IngestRequestProcessor
import octometer.kit.core.ingest.IngestSettings
import octometer.kit.core.path.RoutePatternDerivation

/**
 * The full class name of `io.ktor.server.routing.TransparentRouteSelector`
 * (design decision D46, issue #275). Ktor added this class after the
 * 3.2.0 floor of `README.md` line 24 (confirmed on 2026-09-28 with
 * `javap` against `ktor-server-core-jvm-3.2.0.jar`, where the class is
 * absent, and `ktor-server-core-jvm-3.6.0.jar`, where it is present).
 * [selectorSegment] matches it by name, so a walk against Ktor 3.2.0
 * never loads a class that Ktor 3.2.0 does not ship.
 */
private const val TRANSPARENT_SELECTOR_CLASS_NAME = "io.ktor.server.routing.TransparentRouteSelector"

/**
 * Walks the routing tree of [application] and derives the C42 pattern
 * list of design decision D46 (issue #275). A caller runs this after
 * the routing of the app is complete.
 *
 * @throws IllegalStateException for a selector that this function does
 *   not know (a new Ktor version, or a plugin that adds its own
 *   selector). The caller then falls back to the typed list; see
 *   [installRoutePatternDerivation].
 */
internal fun deriveKtorRoutePatterns(application: Application, ingestPath: String): List<String> {
    val root = application.routingRoot
    val routes = mutableListOf<RoutePatternDerivation.RouteTemplate>()
    collectRouteTemplates(root, emptyList(), routes)
    return RoutePatternDerivation.derive(routes, application.rootPath, ingestPath)
}

private fun collectRouteTemplates(
    node: RoutingNode,
    segmentsSoFar: List<RoutePatternDerivation.Segment>,
    out: MutableList<RoutePatternDerivation.RouteTemplate>,
) {
    for (child in node.children) {
        val method = methodOf(child.selector)
        if (method != null) {
            out.add(RoutePatternDerivation.RouteTemplate(method, segmentsSoFar))
        }
        val childSegments = segmentOf(child.selector)?.let { segmentsSoFar + it } ?: segmentsSoFar
        collectRouteTemplates(child, childSegments, out)
    }
}

/** Gives the HTTP method text of [selector], or `null` when it is not a method selector. */
private fun methodOf(selector: RouteSelector): String? =
    (selector as? HttpMethodRouteSelector)?.method?.value

/**
 * Gives the [RoutePatternDerivation.Segment] of [selector], `null` for
 * a selector that adds no path segment, or throws for a selector this
 * function does not know (design decision D46).
 */
private fun segmentOf(selector: RouteSelector): RoutePatternDerivation.Segment? = when (selector) {
    is PathSegmentConstantRouteSelector -> RoutePatternDerivation.Segment.Literal(selector.value)
    is PathSegmentParameterRouteSelector ->
        RoutePatternDerivation.Segment.Parameter(selector.name, false)
    is PathSegmentOptionalParameterRouteSelector ->
        RoutePatternDerivation.Segment.Parameter(selector.name, true)
    is PathSegmentTailcardRouteSelector -> RoutePatternDerivation.Segment.Wildcard()
    is PathSegmentRegexRouteSelector -> RoutePatternDerivation.Segment.Wildcard()
    is PathSegmentWildcardRouteSelector -> RoutePatternDerivation.Segment.Wildcard()
    is HttpMethodRouteSelector -> null
    is RootRouteSelector -> null
    is TrailingSlashRouteSelector -> null
    else -> if (selector::class.java.name == TRANSPARENT_SELECTOR_CLASS_NAME) {
        null
    } else {
        throw IllegalStateException(
            "The route derivation of design decision D46 found an unknown selector class " +
                "${selector::class.java.name}.",
        )
    }
}

/**
 * Subscribes [application] on [ApplicationStarted], so that the
 * derivation of [deriveKtorRoutePatterns] runs once the whole routing
 * block of the app already ran (design decision D46, issue #275). By
 * this point of the Ktor lifecycle, every module of the app already
 * built its own routes, also a route that a call after
 * `octometerIngestRoute` adds inside the very same `routing {}` block.
 *
 * This function does nothing when [settings] already carries a
 * [IngestSettings.pathPatternMatcher] of its own: the app, or a test,
 * built that matcher by hand, so this function leaves it in place
 * (finding of `IngestRouteTest.kt`, `a route with a path pattern
 * matcher stores the match result`). Derivation runs only for the
 * common case, [settings] with no matcher of its own (`settings =
 * IngestSettings.fromEnvironment()`, the default parameter, with no
 * `OCTOMETER_PATH_PATTERNS`).
 *
 * [IngestRequestProcessor.updateSettings] then replaces only the
 * matcher of [settings]; every other field of [settings] (for example
 * [IngestSettings.recordAnonymousClicks]) survives unchanged
 * ([IngestSettings.withDerivedPathPatterns]). A derivation failure (an
 * unknown selector, or another defect) logs one warning with the
 * exception class only, and it leaves [settings] as it is. [processor]
 * keeps serving requests with no interruption either way; the update
 * takes effect for the next request onward.
 */
internal fun installRoutePatternDerivation(
    application: Application,
    ingestPath: String,
    settings: IngestSettings,
    processor: IngestRequestProcessor,
) {
    if (settings.pathPatternMatcher() != null) {
        return
    }
    application.monitor.subscribe(ApplicationStarted) {
        val updatedSettings = try {
            settings.withDerivedPathPatterns(deriveKtorRoutePatterns(application, ingestPath))
        } catch (cause: Exception) {
            LOGGER.log(
                Level.WARNING,
                "The route derivation of design decision D46 failed. The exception class is {0}. " +
                    "The kit keeps the typed route pattern list.",
                cause::class.java.name,
            )
            settings
        }
        processor.updateSettings(updatedSettings)
    }
}

private val LOGGER: Logger = System.getLogger("octometer.kit.ktor")
