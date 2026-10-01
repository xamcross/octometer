package octometer.monitor.accounts

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** One hour, the window length of the sign-in rate limit (D20, issue #301). */
internal const val RATE_LIMIT_WINDOW_MILLIS = 60L * 60 * 1000

/**
 * A fixed-window rate limiter, in memory, the form of D20. Each key
 * holds a count and the start time of its own window. A window of
 * [windowMillis] resets the count for that key once it ends. [limit]
 * bounds the count inside one window.
 *
 * [recordAndCheck] always counts the call, then reports whether the
 * call stays inside the limit (issue #301, brief review M6: the
 * per-address counter counts every request, listed or not).
 */
class FixedWindowRateLimiter(
    private val windowMillis: Long,
    private val limit: Int,
) {
    private data class Window(val startedAt: Long, val count: AtomicInteger)

    private val windows = ConcurrentHashMap<String, Window>()

    /** Counts one call for [key] at [now], and returns true when it stays at or below the limit. */
    fun recordAndCheck(key: String, now: Long): Boolean {
        val window = windows.compute(key) { _, existing ->
            if (existing == null || now - existing.startedAt >= windowMillis) {
                Window(now, AtomicInteger(1))
            } else {
                existing.count.incrementAndGet()
                existing
            }
        }
        return (window?.count?.get() ?: 0) <= limit
    }
}
