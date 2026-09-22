package octometer.monitor

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/**
 * Runs [block] and returns its result, together with each ERROR-level log
 * event that any logger wrote while it ran (MAJOR 1, third Ktor review).
 * A test then checks that a client mistake never writes an ERROR line.
 */
suspend fun <T> captureErrorLogEvents(block: suspend () -> T): Pair<T, List<ILoggingEvent>> {
    val (result, events) = captureLogEvents(block)
    return result to events.filter { event -> event.level == Level.ERROR }
}

/**
 * Logger names of a known, framework-level TRACE line that names the
 * full request URL, with no tie to issue #61. Each one stays below the
 * `logback.xml` root level of INFO in a real deployment, so it never
 * runs there. A sentinel test at TRACE level must skip it, or every
 * route with a value in its path or its query string would fail.
 *
 * `io.ktor.client`: the WebSockets plugin of the test HTTP client logs
 * each call, from the client side, not the server side.
 *
 * `io.ktor.server.plugins.statuspages.StatusPages`: this plugin logs
 * "No handler found for status code ... for call: <url>" for a normal
 * answer with no registered status handler. This is a framework line,
 * not a monitor line. Issue #150 removes this filter, and it fixes the
 * plugin so it no longer names the URL (MINOR 9, second SQL review of
 * #61).
 */
private val KNOWN_URL_LOGGER_PREFIXES = listOf(
    "io.ktor.client",
    "io.ktor.server.plugins.statuspages.StatusPages",
)

/**
 * Runs [block] and returns its result, together with each log event of
 * every level (issue #61). A test then checks that no line of a route
 * holds a user id or a query string, not only an ERROR line.
 *
 * MINOR 4 of the SQL review: the root level of `logback.xml` is INFO, so
 * a DEBUG line never reaches the appender. This call raises the root
 * level to TRACE for [block], and restores the old level in `finally`.
 */
suspend fun <T> captureLogEvents(block: suspend () -> T): Pair<T, List<ILoggingEvent>> {
    val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    val oldLevel = root.level
    val appender = ListAppender<ILoggingEvent>()
    appender.start()
    root.addAppender(appender)
    root.level = Level.TRACE
    try {
        val result = block()
        val events = appender.list.filterNot { event ->
            KNOWN_URL_LOGGER_PREFIXES.any { prefix -> event.loggerName.startsWith(prefix) }
        }
        return result to events
    } finally {
        root.level = oldLevel
        root.detachAppender(appender)
        appender.stop()
    }
}
