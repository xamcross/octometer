package octometer.monitor.frontend

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Correction round 1 of issue #38 (BLOCKER 1, both reviews): the Angular
 * production build once inlined the critical CSS, and it wrote an inline
 * `onload` handler on the stylesheet `<link>` of `index.html`. The CSP
 * of D12 holds no `script-src`, so `default-src 'self'` blocks that
 * handler, and the browser never applies the page style.
 *
 * `monitor/frontend/angular.json` now sets
 * `optimization.styles.inlineCritical` to `false` in the `production`
 * configuration (issue #38, corrections round 1). This test reads the
 * built `index.html` and fails when an inline handler or an inline
 * script comes back. It skips when nobody has built the frontend yet;
 * run `./gradlew :monitor:backend:buildFrontend` first.
 */
class FrontendCspTest {

    @Test
    fun `the built index html holds no inline onload handler and no inline script`() {
        val indexFile = File("build/frontend-dist/browser/index.html")
        assumeTrue(indexFile.isFile, "Run './gradlew :monitor:backend:buildFrontend' first.")

        val html = indexFile.readText()

        assertFalse(html.contains("onload="), "index.html must hold no onload attribute:\n$html")
        assertFalse(
            Regex("""<script(?![^>]*\bsrc=)[^>]*>[^<]""").containsMatchIn(html),
            "index.html must hold no inline script body:\n$html",
        )
    }
}
