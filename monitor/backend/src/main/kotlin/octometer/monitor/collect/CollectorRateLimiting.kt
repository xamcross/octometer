package octometer.monitor.collect

import java.time.Clock
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern
import octometer.kit.core.ingest.AnonymousDailyCap
import octometer.kit.core.ingest.AnonymousKey
import octometer.kit.core.ingest.AnonymousMinuteLimiter
import octometer.kit.core.ingest.IngestSettings

/**
 * The cap of the dev-only request counter map, and of the per-app daily
 * cap map, with an LRU eviction (the size of design decision D20, the
 * brief review of 2026-10-01, MAJOR 2 and MAJOR 3).
 */
private const val MAX_RATE_LIMITING_ENTRIES = 20_000

/** The maximum length of a client address value, the same bound as `IngestRequestProcessor` of `kit/jvm-core`. */
private const val MAX_CLIENT_ADDRESS_LENGTH = 64

/** The trusted proxy count of the collector: 1, fixed (the brief review of 2026-10-01, M4). */
private const val TRUSTED_PROXY_COUNT = 1

private val IPV4_ADDRESS_PATTERN: Pattern = Pattern.compile(
    "^(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])(\\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}$",
)

private val IPV6_ADDRESS_PATTERN: Pattern = Pattern.compile(
    "^(([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,7}:|([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|" +
        "([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}|" +
        "([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}|([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|" +
        "[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})|:((:[0-9a-fA-F]{1,4}){1,7}|:)|" +
        "::(ffff(:0{1,4})?:)?((25[0-5]|(2[0-4]|1?[0-9])?[0-9])\\.){3}(25[0-5]|(2[0-4]|1?[0-9])?[0-9])|" +
        "([0-9a-fA-F]{1,4}:){1,4}:((25[0-5]|(2[0-4]|1?[0-9])?[0-9])\\.){3}(25[0-5]|(2[0-4]|1?[0-9])?[0-9]))$",
)

/**
 * The client address of one request (design decision D20, issue #297;
 * the brief review of 2026-10-01, M4). This is the same rule as the
 * trusted-proxy logic of `IngestRequestProcessor.clientAddress` of
 * `kit/jvm-core` (a package-private method of a different Gradle
 * module, so this function reuses the rule, not the code): the header
 * that [clientIpHeaderName] names, the rightmost value of its last
 * line, counted with a trusted proxy count fixed at [TRUSTED_PROXY_COUNT]
 * (one trusted proxy: fly-proxy); else the remote address. The service
 * never trusts `X-Forwarded-For` (design decision D50, B6): it reads
 * only the header that [clientIpHeaderName] names.
 *
 * [headerLines] is every line of the request header named
 * [clientIpHeaderName], in receipt order (empty when the request holds
 * none, or when [clientIpHeaderName] is blank).
 */
fun clientAddressOf(headerLines: List<String>, remoteAddress: String): String {
    if (headerLines.isNotEmpty()) {
        val lastLine = headerLines.last()
        val elements = lastLine.split(",")
        val index = elements.size - TRUSTED_PROXY_COUNT
        if (index in elements.indices) {
            val candidate = elements[index].trim()
            if (candidate.isNotEmpty() && candidate.length <= MAX_CLIENT_ADDRESS_LENGTH && looksLikeAnIpAddress(candidate)) {
                return candidate
            }
        }
    }
    return remoteAddress
}

private fun looksLikeAnIpAddress(value: String): Boolean =
    IPV4_ADDRESS_PATTERN.matcher(value).matches() || IPV6_ADDRESS_PATTERN.matcher(value).matches()

/**
 * The one shared service-wide [AnonymousMinuteLimiter] of design
 * decision D43, the brief review of 2026-10-01 (M4, m4): one instance
 * for the whole service, not one for each app. [CollectRoute] keys each
 * check on the app id plus the client key, so two apps, or two clients
 * of one app, never share one counter ([keyOf]).
 *
 * MAJOR 3 of the review of pull request #327: a daily cap needs one
 * [AnonymousDailyCap] for each app id, not one shared instance. The
 * class's own "global" counter must count one app's events only (the
 * per-app cap of design decision D50, the same 20 000 bound as
 * [DAILY_EVENT_CAP] of [DailyEventCountStore]), never the whole
 * service; one shared instance made the whole service share one
 * 20 000-event daily budget instead. [dailyCapOf] gives one instance
 * for each app id, from an LRU-bounded map (the size of design decision
 * D20), so this map too can never grow without a bound.
 *
 * Design decision D19 is on by default for the hosted model (the
 * maintainer's note of 2026-10-01): every request of this story is
 * anonymous (no `userId`, B3), so the collector applies design decision
 * D43 to every request and never design decision D20's plain rate
 * limiter, the same rule as `IngestRequestProcessor.beforeBody` of
 * `kit/jvm-core` when `settings.recordAnonymousClicks()` is `true`.
 */
class CollectorRateLimiting(private val clock: Clock) {

    val minuteLimiter: AnonymousMinuteLimiter = AnonymousMinuteLimiter(
        clock,
        IngestSettings.DEFAULT_ANON_REQ_PER_MIN,
        IngestSettings.DEFAULT_ANON_EVENTS_PER_MIN,
        IngestSettings.DEFAULT_ANON_SESSIONS_PER_MIN,
    )

    private val dailyCapsByAppId = LruMap<AnonymousDailyCap>(MAX_RATE_LIMITING_ENTRIES)

    /**
     * The daily cap of [appId] (MAJOR 3): a fresh [AnonymousDailyCap]
     * the first time this app id is seen, with [DAILY_EVENT_CAP] as its
     * own "global" bound (one app's whole daily budget, design decision
     * D50) and [IngestSettings.DEFAULT_ANON_EVENTS_PER_KEY_PER_DAY] as
     * the bound of one client key inside that app (design decision
     * D43), after that from the LRU map.
     */
    fun dailyCapOf(appId: Long): AnonymousDailyCap =
        dailyCapsByAppId.getOrPut(appId.toString()) {
            AnonymousDailyCap(clock, DAILY_EVENT_CAP, IngestSettings.DEFAULT_ANON_EVENTS_PER_KEY_PER_DAY)
        }

    /**
     * The shared key of [minuteLimiter] and of [dailyCapOf] for [appId]
     * and [clientAddress] (M4, m4). [AnonymousKey.of] collapses
     * [clientAddress] to its design decision D43 form (the IPv6 /64
     * prefix) before the app id joins it, so the IPv6 collapse still
     * runs on the real address text, never on an app-qualified text.
     */
    fun keyOf(appId: Long, clientAddress: String): String = "$appId:${AnonymousKey.of(clientAddress)}"

    // The dev-only counters of GET /api/dev/collector-counters (the
    // brief review of 2026-10-01, M5): kit/jvm-core keeps no public
    // getter for one key's own count (AnonymousMinuteLimiter.keyCount()
    // reads the map size only, and it is package-private). This map is
    // the monitor's own count of requests seen for one rate-limit key,
    // for the end-to-end scenario's own assertion; it is not part of
    // the rate limit decision itself. MAJOR 2 of the review of pull
    // request #327: an LRU bound (the size of design decision D20), so
    // a flood of invented keys can never grow this map without a bound
    // either.
    private val requestCounts = LruMap<AtomicLong>(MAX_RATE_LIMITING_ENTRIES)

    /** Records one request of [rateLimitKey] for the dev-only counters. */
    fun recordRequest(rateLimitKey: String) {
        requestCounts.getOrPut(rateLimitKey) { AtomicLong() }.incrementAndGet()
    }

    /** A snapshot of every counter that [recordRequest] built, for `GET /api/dev/collector-counters`. */
    fun requestCountSnapshot(): Map<String, Long> = requestCounts.snapshot().mapValues { it.value.get() }
}
