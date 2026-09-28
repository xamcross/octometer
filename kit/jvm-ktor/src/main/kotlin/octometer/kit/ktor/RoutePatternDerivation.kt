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
 * An unknown selector class (a new Ktor version, or a plugin that adds
 * its own selector, other than a `TransparentRouteSelector` subclass)
 * skips only its own subtree, with one warning; every other route of
 * the app still joins the list (MAJOR 1 of the correction round of
 * issue #275).
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
        try {
            val method = methodOf(child.selector)
            if (method != null) {
                out.add(RoutePatternDerivation.RouteTemplate(method, segmentsSoFar))
            }
            val childSegments = segmentOf(child.selector)?.let { segmentsSoFar + it } ?: segmentsSoFar
            collectRouteTemplates(child, childSegments, out)
        } catch (cause: UnknownRouteSelectorException) {
            LOGGER.log(
                Level.WARNING,
                "The route derivation of design decision D46 found an unknown selector class {0}. " +
                    "The kit skips this one route subtree, and it keeps every other route.",
                cause.selectorClassName,
            )
        }
    }
}

/** Gives the HTTP method text of [selector], or `null` when it is not a method selector. */
private fun methodOf(selector: RouteSelector): String? =
    (selector as? HttpMethodRouteSelector)?.method?.value

/**
 * Gives the [RoutePatternDerivation.Segment] of [selector], or `null`
 * for a selector that adds no path segment. A `TransparentRouteSelector`
 * subclass, by class name (design decision D46, MAJOR 1 of the
 * correction round of issue #275), also gives `null`: it covers, for
 * example, the Ktor auth plugin's `authenticate {}` selector, and the
 * `header`, `accept`, `param`, and `host` selectors.
 *
 * @throws UnknownRouteSelectorException for a selector this function
 *   does not know. [collectRouteTemplates] catches it and skips only
 *   the one subtree below that selector.
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
    else -> if (isTransparentSelectorClass(selector::class.java)) {
        null
    } else {
        throw UnknownRouteSelectorException(selector::class.java.name)
    }
}

/**
 * True for [selectorClass] itself, or for any of its superclasses, by
 * class name, equal to [TRANSPARENT_SELECTOR_CLASS_NAME] (design
 * decision D46, MAJOR 1 of the correction round of issue #275). This
 * function reads only the name of each superclass, so it matches a
 * subclass that a Ktor plugin adds too, for example the auth plugin's
 * `AuthenticationRouteSelector`, with no compile dependency on that
 * plugin.
 */
private tailrec fun isTransparentSelectorClass(selectorClass: Class<*>): Boolean {
    if (selectorClass.name == TRANSPARENT_SELECTOR_CLASS_NAME) {
        return true
    }
    val superclass = selectorClass.superclass ?: return false
    return isTransparentSelectorClass(superclass)
}

/**
 * Marks a [RouteSelector] class that [segmentOf] does not know (design
 * decision D46). [collectRouteTemplates] catches this exception and
 * skips only the one subtree below that selector; every other route of
 * the app still joins the list (MAJOR 1 of the correction round of
 * issue #275).
 */
private class UnknownRouteSelectorException(val selectorClassName: String) : IllegalStateException(
    "The route derivation of design decision D46 found an unknown selector class $selectorClassName.",
)

/**
 * Subscribes [application] on [ApplicationStarted], so that the
 * derivation of [deriveKtorRoutePatterns] runs once the whole routing
 * block of the app already ran (design decision D46, issue #275). By
 * this point of the Ktor lifecycle, every module of the app already
 * built its own routes, also a route that a call after
 * `octometerIngestRoute` adds inside the very same `routing {}` block.
 *
 * This function always derives, also when [settings] already carries
 * a `path` matcher that [IngestSettings.fromEnvironment] built from
 * `OCTOMETER_PATH_PATTERNS`: [IngestSettings.withDerivedPathPatterns]
 * reads that same environment variable again and appends its entries
 * after the derived list, never before it (design decision D46,
 * BLOCKER 1 of the correction round of issue #275). The caller of
 * `octometerIngestRoute` skips this call, with `deriveRoutePatterns =
 * false`, for a [settings] value with a `path` matcher that the app,
 * or a test, builds by hand (finding of `IngestRouteTest.kt`, `a route
 * with a path pattern matcher stores the match result`).
 *
 * [IngestRequestProcessor.updateSettings] then replaces only the
 * matcher of [settings]; every other field of [settings] (for example
 * [IngestSettings.recordAnonymousClicks]) survives unchanged
 * ([IngestSettings.withDerivedPathPatterns]). A derivation failure (an
 * unexpected exception, beyond the per-subtree skip of an unknown
 * selector; see [collectRouteTemplates]) logs one warning with the
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
