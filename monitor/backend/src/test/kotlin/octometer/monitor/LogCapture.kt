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
    val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    val appender = ListAppender<ILoggingEvent>()
    appender.start()
    root.addAppender(appender)
    try {
        val result = block()
        return result to appender.list.filter { event -> event.level == Level.ERROR }
    } finally {
        root.detachAppender(appender)
        appender.stop()
    }
}

/**
 * Runs [block] and returns its result, together with each log event of
 * every level (issue #61). A test then checks that no line of a route
 * holds a user id or a query string, not only an ERROR line.
 */
suspend fun <T> captureLogEvents(block: suspend () -> T): Pair<T, List<ILoggingEvent>> {
    val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    val appender = ListAppender<ILoggingEvent>()
    appender.start()
    root.addAppender(appender)
    try {
        val result = block()
        return result to appender.list.toList()
    } finally {
        root.detachAppender(appender)
        appender.stop()
    }
}
