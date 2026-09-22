package octometer.kit.core.ingest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of {@link EventFieldValidator#isValidPath} against rule C39. Each
 * test method name states the exact shape rule that it checks (issue
 * #103).
 */
class EventFieldValidatorPathTest {

    @Test
    void acceptsAPathOfOneCharacter() {
        assertTrue(EventFieldValidator.isValidPath("/"));
    }

    @Test
    void acceptsAPathWithEachAllowedPunctuationCharacter() {
        assertTrue(EventFieldValidator.isValidPath("/a._~!$&'()*+,;=:@-b"));
    }

    @Test
    void acceptsAPathWithAWellFormedEscape() {
        assertTrue(EventFieldValidator.isValidPath("/articles/caf%C3%A9"));
    }

    @Test
    void acceptsAPathOfExactly150Bytes() {
        String path = "/" + "a".repeat(149);
        assertTrue(EventFieldValidator.isValidPath(path));
    }

    @Test
    void rejectsAPathOf151Bytes() {
        String path = "/" + "a".repeat(150);
        assertFalse(EventFieldValidator.isValidPath(path));
    }

    @Test
    void rejectsAnEmptyPath() {
        assertFalse(EventFieldValidator.isValidPath(""));
    }

    @Test
    void rejectsAPathThatDoesNotStartWithASlash() {
        assertFalse(EventFieldValidator.isValidPath("articles/example"));
    }

    @Test
    void rejectsAPathWithASecondSlash() {
        assertFalse(EventFieldValidator.isValidPath("//articles/example"));
    }

    @Test
    void rejectsAPathWithAQuestionMark() {
        assertFalse(EventFieldValidator.isValidPath("/articles?id=1"));
    }

    @Test
    void rejectsAPathWithAFragment() {
        assertFalse(EventFieldValidator.isValidPath("/articles#top"));
    }

    @Test
    void rejectsAPathWithAPartialEscapeAtTheEnd() {
        assertFalse(EventFieldValidator.isValidPath("/articles/caf%C"));
        assertFalse(EventFieldValidator.isValidPath("/articles/caf%"));
    }

    @Test
    void rejectsAPathWithABadEscapeDigit() {
        assertFalse(EventFieldValidator.isValidPath("/articles/caf%ZZ"));
    }
}
