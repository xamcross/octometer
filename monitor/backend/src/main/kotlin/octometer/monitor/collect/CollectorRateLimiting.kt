package octometer.monitor.collect

import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern
import octometer.kit.core.ingest.AnonymousDailyCap
import octometer.kit.core.ingest.AnonymousKey
import octometer.kit.core.ingest.AnonymousMinuteLimiter
import octometer.kit.core.ingest.IngestSettings

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
 * The one shared service-wide limiter pair of design decision D43, the
 * brief review of 2026-10-01 (M4, m4): one [AnonymousMinuteLimiter] and
 * one [AnonymousDailyCap] for the whole service, not one pair for each
 * app. [CollectRoute] keys each check on the app id plus the client
 * key, so two apps, or two clients of one app, never share one counter
 * ([keyOf]).
 *
 * Design decision D19 is on by default for the hosted model (the
 * maintainer's note of 2026-10-01): every request of this story is
 * anonymous (no `userId`, B3), so the collector applies design decision
 * D43 to every request and never design decision D20's plain rate
 * limiter, the same rule as `IngestRequestProcessor.beforeBody` of
 * `kit/jvm-core` when `settings.recordAnonymousClicks()` is `true`.
 */
class CollectorRateLimiting(clock: Clock) {

    val minuteLimiter: AnonymousMinuteLimiter = AnonymousMinuteLimiter(
        clock,
        IngestSettings.DEFAULT_ANON_REQ_PER_MIN,
        IngestSettings.DEFAULT_ANON_EVENTS_PER_MIN,
        IngestSettings.DEFAULT_ANON_SESSIONS_PER_MIN,
    )

    val dailyCap: AnonymousDailyCap = AnonymousDailyCap(
        clock,
        IngestSettings.DEFAULT_ANON_MAX_EVENTS_PER_DAY,
        IngestSettings.DEFAULT_ANON_EVENTS_PER_KEY_PER_DAY,
    )

    /**
     * The shared key of [minuteLimiter] and [dailyCap] for [appId] and
     * [clientAddress] (M4, m4). [AnonymousKey.of] collapses
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
    // the rate limit decision itself.
    private val requestCounts = ConcurrentHashMap<String, AtomicLong>()

    /** Records one request of [rateLimitKey] for the dev-only counters. */
    fun recordRequest(rateLimitKey: String) {
        requestCounts.computeIfAbsent(rateLimitKey) { AtomicLong() }.incrementAndGet()
    }

    /** A snapshot of every counter that [recordRequest] built, for `GET /api/dev/collector-counters`. */
    fun requestCountSnapshot(): Map<String, Long> = requestCounts.mapValues { it.value.get() }
}
