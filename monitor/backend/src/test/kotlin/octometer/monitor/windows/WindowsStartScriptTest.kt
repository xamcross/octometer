package octometer.monitor.windows

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Correction round 1 of issue #38 (MAJOR 4, release review): the earlier
 * `doLast` block of `startScripts` rewrote `backend.bat` with no check
 * that the rewrite ran. This test reads the generated file and fails
 * when the wildcard class path line is missing.
 *
 * I broke the regex of `build.gradle.kts` for a moment (a text that
 * matches no line of the template), ran
 * `./gradlew :monitor:backend:startScripts`, ran this test, and saw it
 * fail: the file held the long explicit class path instead. I restored
 * the regex, reran `startScripts`, and saw this test pass.
 *
 * It skips when nobody has run `installDist` yet.
 */
class WindowsStartScriptTest {

    @Test
    fun `the generated backend bat holds the wildcard class path line`() {
        val scriptFile = File("build/install/backend/bin/backend.bat")
        assumeTrue(scriptFile.isFile, "Run './gradlew :monitor:backend:installDist' first.")

        val lines = scriptFile.readLines()

        assertTrue(
            lines.any { it.trim() == "set CLASSPATH=%APP_HOME%\\lib\\*" },
            "backend.bat holds no 'set CLASSPATH=%APP_HOME%\\lib\\*' line",
        )
    }
}
