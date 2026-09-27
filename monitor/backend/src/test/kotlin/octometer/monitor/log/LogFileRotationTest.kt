package octometer.monitor.log

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.util.LogbackMDCAdapter
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy
import ch.qos.logback.core.util.FileSize
import java.io.File
import java.nio.file.Files
import octometer.monitor.registerTempRoot
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #57, decision 5 of the maintainer's correction, and MAJOR 1 of
 * the JVM operations review of pull request #231: this class builds
 * its own [LoggerContext] from `logback-file.xml`, never from the
 * shared context of [octometer.monitor.captureLogEvents]. `logback.xml`
 * alone holds the two console appenders only, with no file appender.
 * `logback-file.xml` includes `logback.xml`, then adds the file
 * appender; [octometer.monitor.Application]'s own `configureLogDir`
 * loads the same file, once `dataDir` is known.
 *
 * Each policy property comes from the live appender object, not from
 * the XML text (the acceptance criterion of issue #57). Logback 1.6.3
 * gives no public getter for `maxFileSize` or `totalSizeCap`, so
 * [maxFileSizeOf] and [totalSizeCapOf] read the field through
 * reflection. This test writes no 10 MB file to force a rollover; the
 * policy values are the proof (design decision D37).
 */
class LogFileRotationTest {

    private val root = Files.createTempDirectory("octometer-log-rotation-test-").toFile().also { registerTempRoot(it) }
    private lateinit var context: LoggerContext

    @AfterTest
    fun tearDown() {
        if (::context.isInitialized) {
            context.stop()
        }
        root.deleteRecursively()
    }

    @Test
    fun `the file octometer_log exists and holds the line after one INFO event`() {
        context = configuredContext(root)
        val logger = context.getLogger("octometer.monitor.log.LogFileRotationTest")

        logger.info("a marker line of the rotation test")

        val file = File(root, "octometer.log")
        assertTrue(file.isFile, "the file octometer.log exists after one INFO line")
        assertTrue(
            file.readText().contains("a marker line of the rotation test"),
            "the file holds the line",
        )
    }

    @Test
    fun `the file appender is a RollingFileAppender with the rotation policy of D37`() {
        context = configuredContext(root)

        val rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME)
        val fileAppender = rootLogger.getAppender("FILE")
        assertTrue(fileAppender is RollingFileAppender<*>, "the appender FILE is a RollingFileAppender")

        val policy = (fileAppender as RollingFileAppender<*>).rollingPolicy
        assertTrue(policy is SizeAndTimeBasedRollingPolicy<*>, "the policy is a SizeAndTimeBasedRollingPolicy")

        assertEquals(10L * FileSize.MB_COEFFICIENT, maxFileSizeOf(policy).size, "maxFileSize is 10 MB")
        assertEquals(14, policy.maxHistory, "maxHistory is 14 days")
        assertEquals(200L * FileSize.MB_COEFFICIENT, totalSizeCapOf(policy).size, "totalSizeCap is 200 MB")
        assertTrue(policy.isCleanHistoryOnStart, "cleanHistoryOnStart is true")
    }
}

/**
 * Builds a fresh [LoggerContext] from `logback-file.xml`, with
 * `OCTOMETER_LOG_DIR` set as a context property to [logDir]. A
 * context property, not a system property, so this test never leaks
 * a value into a different test of the same JVM.
 *
 * A plain `LoggerContext()` holds no MDC adapter; the normal SLF4J
 * start-up call sets one on the shared context. This function sets
 * one by hand, or each event throws inside the appender (a
 * `NullPointerException` of `MDCAdapter.getCopyOfContextMap`).
 */
private fun configuredContext(logDir: File): LoggerContext {
    val context = LoggerContext()
    context.mdcAdapter = LogbackMDCAdapter()
    context.putProperty("OCTOMETER_LOG_DIR", logDir.absolutePath)
    val configurator = JoranConfigurator()
    configurator.context = context
    val resource = Thread.currentThread().contextClassLoader.getResource("logback-file.xml")
        ?: error("The file logback-file.xml is missing from the classpath.")
    configurator.doConfigure(resource)
    return context
}

/** Reads the field `maxFileSize` of [policy] (no public getter in Logback 1.6.3). */
private fun maxFileSizeOf(policy: SizeAndTimeBasedRollingPolicy<*>): FileSize {
    val field = SizeAndTimeBasedRollingPolicy::class.java.getDeclaredField("maxFileSize")
    field.isAccessible = true
    return field.get(policy) as FileSize
}

/** Reads the field `totalSizeCap` of [policy]'s parent class (no public getter in Logback 1.6.3). */
private fun totalSizeCapOf(policy: SizeAndTimeBasedRollingPolicy<*>): FileSize {
    val field = TimeBasedRollingPolicy::class.java.getDeclaredField("totalSizeCap")
    field.isAccessible = true
    return field.get(policy) as FileSize
}
