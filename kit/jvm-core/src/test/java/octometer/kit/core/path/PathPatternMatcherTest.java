package octometer.kit.core.path;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of {@link PathPatternMatcher} against the implementation steps of
 * issue #104 and contract rule C42. The list of the acceptance criteria
 * uses the pattern list `/`, `/articles`, `/articles/*`, `/history/:id`,
 * `/ovdp/rates`.
 */
class PathPatternMatcherTest {

    private static final List<String> SAMPLE_PATTERNS =
            List.of("/", "/articles", "/articles/*", "/history/:id", "/ovdp/rates");

    @Test
    void aNamedSegmentMasksTheRealValue() {
        PathPatternMatcher matcher = PathPatternMatcher.of(SAMPLE_PATTERNS);

        assertEquals("/history/:id", matcher.match("/history/42"));
    }

    @Test
    void aStarSegmentKeepsAValidRealValue() {
        PathPatternMatcher matcher = PathPatternMatcher.of(SAMPLE_PATTERNS);

        assertEquals("/articles/ovdp-2026", matcher.match("/articles/ovdp-2026"));
    }

    @Test
    void aTrailingSlashGoesBeforeTheMatchAndTheMatchIgnoresTheLetterCase() {
        PathPatternMatcher matcher = PathPatternMatcher.of(SAMPLE_PATTERNS);

        assertEquals("/articles", matcher.match("/ARTICLES/"));
    }

    @Test
    void theRootPathKeepsItsOwnTrailingSlashRule() {
        PathPatternMatcher matcher = PathPatternMatcher.of(SAMPLE_PATTERNS);

        assertEquals("/", matcher.match("/"));
    }

    @Test
    void theStoredValueUsesTheTextOfThePatternNeverTheTextOfTheInput() {
        PathPatternMatcher matcher = PathPatternMatcher.of(List.of("/Articles"));

        assertEquals("/Articles", matcher.match("/articles"));
    }

    @Test
    void aPathWithNoMatchingPatternGivesOther() {
        PathPatternMatcher matcher = PathPatternMatcher.of(SAMPLE_PATTERNS);

        assertEquals("/other", matcher.match("/tokens/abc"));
    }

    @Test
    void aDifferentSegmentCountNeverMatchesTheStarPattern() {
        PathPatternMatcher matcher = PathPatternMatcher.of(List.of("/articles/*"));

        assertEquals("/other", matcher.match("/articles"));
        assertEquals("/other", matcher.match("/articles/a/b"));
    }

    @Test
    void aStarSegmentOf81CharactersGivesOther() {
        PathPatternMatcher matcher = PathPatternMatcher.of(SAMPLE_PATTERNS);

        assertEquals("/other", matcher.match("/articles/" + "a".repeat(81)));
    }

    @Test
    void aStarSegmentOf80CharactersStaysValid() {
        PathPatternMatcher matcher = PathPatternMatcher.of(SAMPLE_PATTERNS);

        assertEquals("/articles/" + "a".repeat(80), matcher.match("/articles/" + "a".repeat(80)));
    }

    @Test
    void anEmptyADotAndADotDotSegmentEachGiveOther() {
        PathPatternMatcher matcher = PathPatternMatcher.of(SAMPLE_PATTERNS);

        assertEquals("/other", matcher.match("/articles//x"));
        assertEquals("/other", matcher.match("/articles/."));
        assertEquals("/other", matcher.match("/articles/.."));
    }

    @Test
    void theMatcherNeverDecodesAPercentEscape() {
        PathPatternMatcher matcher = PathPatternMatcher.of(List.of("/articles/*"));

        assertEquals("/articles/e%CC%81t%C3%A9", matcher.match("/articles/e%CC%81t%C3%A9"));
    }

    @Test
    void theFirstPatternThatMatchesWins() {
        PathPatternMatcher forward = PathPatternMatcher.of(List.of("/articles/*", "/articles/:id"));
        PathPatternMatcher reverse = PathPatternMatcher.of(List.of("/articles/:id", "/articles/*"));

        assertEquals("/articles/abc", forward.match("/articles/abc"));
        assertEquals("/articles/:id", reverse.match("/articles/abc"));
    }

    @Test
    void isValidPatternAcceptsAWellFormedPercentEscapeInALiteralSegment() {
        assertTrue(PathPatternMatcher.isValidPattern("/caf%C3%A9"));
    }

    @Test
    void isValidPatternRejectsAPatternWithNoLeadingSlash() {
        assertFalse(PathPatternMatcher.isValidPattern("articles"));
    }

    @Test
    void isValidPatternRejectsAnEmptyText() {
        assertFalse(PathPatternMatcher.isValidPattern(""));
    }

    @Test
    void findInvalidIndicesNamesEachBadEntry() {
        List<Integer> invalidIndices =
                PathPatternMatcher.findInvalidIndices(List.of("/articles", "no-slash", "/ovdp/rates", "also-bad"));

        assertEquals(List.of(1, 3), invalidIndices);
    }

    @Test
    void findInvalidIndicesIsEmptyForAWellFormedList() {
        assertTrue(PathPatternMatcher.findInvalidIndices(SAMPLE_PATTERNS).isEmpty());
    }

    @Test
    void ofRejectsAnInvalidPattern() {
        assertThrows(IllegalArgumentException.class, () -> PathPatternMatcher.of(List.of("no-slash")));
    }

    @Test
    void aResultOfExactly150BytesStays() {
        PathPatternMatcher matcher = PathPatternMatcher.of(List.of("/a/*/*"));
        String result = matcher.match("/a/" + "a".repeat(80) + "/" + "b".repeat(66));

        assertEquals(150, result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertEquals("/a/" + "a".repeat(80) + "/" + "b".repeat(66), result);
    }

    @Test
    void aResultOf151BytesGivesOther() {
        PathPatternMatcher matcher = PathPatternMatcher.of(List.of("/a/*/*"));
        String result = matcher.match("/a/" + "a".repeat(80) + "/" + "b".repeat(67));

        assertEquals("/other", result);
    }
}
