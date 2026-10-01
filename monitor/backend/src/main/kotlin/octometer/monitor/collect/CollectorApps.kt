package octometer.monitor.collect

import java.sql.Connection
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import octometer.kit.core.path.PathPatternMatcher
import octometer.monitor.registry.decodeOriginsJson
import octometer.monitor.store.SqliteDatabase

/** The read cache time of the app lookup and of the route pattern list (issue #297, D50). */
const val APP_CACHE_TTL_MILLIS = 10_000L

/**
 * One app of the registry, as the collector of issue #297 needs it: the
 * database it writes into, the origins of the Origin check (D52), and
 * the typed route pattern list of design decision D40 (`null` until
 * issue #299 gives the app a way to set it; the default rule of
 * `octometer.kit.core.path.DefaultRoutePattern` then applies to every
 * path).
 */
data class CollectorApp(
    val appId: Long,
    val databaseName: String,
    val origins: List<String>,
    val routePatternMatcher: PathPatternMatcher?,
)

/**
 * The app lookup of `X-Octometer-Key` (design decision D50), with a
 * read cache of [APP_CACHE_TTL_MILLIS] per key (the brief review of
 * 2026-10-01, m3). A request inside the cache window reads no SQLite
 * statement; this keeps the hot path of the collector cheap.
 *
 * This class also gives the union of every registered app's origins
 * (the same cache), for a CORS preflight that carries no app key (a
 * browser's `OPTIONS /collect` never repeats a custom header's value).
 */
class AppKeyCache(private val database: SqliteDatabase, private val clock: Clock = Clock.systemUTC()) {

    private data class Entry(val app: CollectorApp?, val cachedAtMillis: Long)

    private val byAppKey = ConcurrentHashMap<String, Entry>()

    @Volatile
    private var originsUnionCachedAtMillis: Long = Long.MIN_VALUE

    @Volatile
    private var originsUnion: Set<String> = emptySet()

    /** Gives the app of [appKey], or `null` for an unknown key. */
    suspend fun lookup(appKey: String): CollectorApp? {
        val now = clock.millis()
        val cached = byAppKey[appKey]
        if (cached != null && now - cached.cachedAtMillis < APP_CACHE_TTL_MILLIS) {
            return cached.app
        }
        val fresh = database.read { reader -> readApp(reader, appKey) }
        byAppKey[appKey] = Entry(fresh, now)
        return fresh
    }

    /**
     * Gives the union of every registered app's origins, refreshed at
     * most one time in [APP_CACHE_TTL_MILLIS] (the preflight of
     * `OPTIONS /collect` and `OPTIONS /tracker.js`, the brief review of
     * 2026-10-01, B2).
     */
    suspend fun allOrigins(): Set<String> {
        val now = clock.millis()
        if (now - originsUnionCachedAtMillis >= APP_CACHE_TTL_MILLIS) {
            originsUnion = database.read { reader -> readAllOrigins(reader) }
            originsUnionCachedAtMillis = now
        }
        return originsUnion
    }

    private fun readApp(reader: Connection, appKey: String): CollectorApp? =
        reader.prepareStatement(
            "SELECT id, database_name, origins, route_patterns FROM app WHERE app_key = ?",
        ).use { select ->
            select.setString(1, appKey)
            select.executeQuery().use { result ->
                if (!result.next()) return@use null
                val databaseName = result.getString("database_name")
                if (databaseName.isNullOrBlank()) return@use null
                CollectorApp(
                    appId = result.getLong("id"),
                    databaseName = databaseName,
                    origins = decodeOriginsJson(result.getString("origins")),
                    routePatternMatcher = routePatternMatcherOf(result.getString("route_patterns")),
                )
            }
        }

    private fun readAllOrigins(reader: Connection): Set<String> =
        reader.prepareStatement("SELECT origins FROM app WHERE app_key IS NOT NULL").use { select ->
            select.executeQuery().use { result ->
                val union = mutableSetOf<String>()
                while (result.next()) {
                    union += decodeOriginsJson(result.getString("origins"))
                }
                union
            }
        }
}

/**
 * Parses the stored `route_patterns` column into a [PathPatternMatcher]
 * (design decision D40). The column holds a JSON list of pattern texts;
 * `null` or a blank text, or a list with one invalid entry, gives
 * `null`, the same rule as the contract's own C42: a broken list counts
 * as no list at all, and the collector then applies the default rule.
 * Issue #299 adds the one writer of this column; this class only reads
 * it.
 */
private fun routePatternMatcherOf(rawJson: String?): PathPatternMatcher? {
    if (rawJson.isNullOrBlank()) return null
    val patterns = try {
        Json.decodeFromString<List<String>>(rawJson)
    } catch (malformed: SerializationException) {
        return null
    }
    if (patterns.isEmpty() || PathPatternMatcher.findInvalidIndices(patterns).isNotEmpty()) {
        return null
    }
    return PathPatternMatcher.of(patterns)
}
