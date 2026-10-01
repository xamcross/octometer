package octometer.monitor.mongo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Issue #226, decision 2 of the correction round of 2026-09-27: the
// route pattern list validation. This class holds one case for each
// rule, plus the normalized text and a few named patterns of contract
// rule C42.
class PathPatternListTest {

    @Test
    fun `parse rejects a text with zero patterns after the split`() {
        val result = PathPatternList.parse("   ")

        val rejected = assertIs<PathPatternListResult.Rejected>(result)
        assertTrue(rejected.message.contains("at least one"), "the reason names the empty-list rule")
    }

    @Test
    fun `parse rejects a list above 200 patterns`() {
        val text = (1..201).joinToString(" ") { index -> "/p$index" }

        val result = PathPatternList.parse(text)

        val rejected = assertIs<PathPatternListResult.Rejected>(result)
        assertTrue(rejected.message.contains("200"), "the reason names the count cap")
    }

    @Test
    fun `parse rejects a pattern above 150 bytes, and names its index`() {
        val tooLong = "/" + "a".repeat(150)
        val text = "/ok $tooLong"

        val result = PathPatternList.parse(text)

        val rejected = assertIs<PathPatternListResult.Rejected>(result)
        assertTrue(rejected.message.contains("index 1"), "the reason names the index of the bad pattern")
        assertTrue(rejected.message.contains("byte length"), "the reason names the byte length rule")
        assertTrue(!rejected.message.contains(tooLong), "the reason never holds the pattern text")
    }

    @Test
    fun `parse rejects a pattern outside the C42 grammar, and names its index`() {
        val result = PathPatternList.parse("/ok /a?b")

        val rejected = assertIs<PathPatternListResult.Rejected>(result)
        assertTrue(rejected.message.contains("index 1"), "the reason names the index of the bad pattern")
        assertTrue(rejected.message.contains("grammar"), "the reason names the grammar rule")
        assertTrue(!rejected.message.contains("/a?b"), "the reason never holds the pattern text")
    }

    @Test
    fun `parse gives the normalized text, one pattern per line, with no blank line`() {
        val result = PathPatternList.parse("  /articles/*   /history/:id \n /  ")

        val accepted = assertIs<PathPatternListResult.Accepted>(result)
        assertEquals("/articles/*\n/history/:id\n/", accepted.normalizedText)
    }

    @Test
    fun `parse accepts a star pattern, a name pattern, and the root pattern`() {
        val result = PathPatternList.parse("/articles/* /history/:id /")

        assertIs<PathPatternListResult.Accepted>(result)
    }

    @Test
    fun `parse treats a comma inside a pattern as one pattern`() {
        val result = PathPatternList.parse("/a,b")

        val accepted = assertIs<PathPatternListResult.Accepted>(result)
        assertEquals("/a,b", accepted.normalizedText)
    }

    // Issue #299, the correction of the brief review of 2026-10-01: the
    // duplicate rule of PUT /api/apps/{id}/routes.
    @Test
    fun `parse rejects a pattern that appears twice, and names the index of the second one`() {
        val result = PathPatternList.parse("/orders/:id /checkout /orders/:id")

        val rejected = assertIs<PathPatternListResult.Rejected>(result)
        assertTrue(rejected.message.contains("index 2"), "the reason names the index of the duplicate pattern")
        assertTrue(rejected.message.contains("twice"), "the reason names the duplicate rule")
    }
}
