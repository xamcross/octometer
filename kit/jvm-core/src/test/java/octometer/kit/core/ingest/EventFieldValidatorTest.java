package octometer.kit.core.ingest;

import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the field rules of section 4.1 (C4, C5, C6). Two tests read the
 * event document examples of `contract/examples/` (issue #8).
 */
class EventFieldValidatorTest {

    @Test
    void acceptsTheFieldsOfTheValidEventExample() {
        String json = ExampleFiles.read("event-valid-C1-C5.json");
        String element = ExampleFiles.extractStringField(json, "element");
        String sessionId = ExampleFiles.extractStringField(json, "sessionId");

        assertDoesNotThrow(() -> EventFieldValidator.validateElement(element));
        assertDoesNotThrow(() -> EventFieldValidator.validateSessionId(sessionId));
    }

    @Test
    void acceptsTheNullUserIdOfTheEventExample() {
        String json = ExampleFiles.read("event-userid-null-C6.json");

        assertTrue(ExampleFiles.hasNullField(json, "userId"));
        assertDoesNotThrow(() -> EventFieldValidator.validateUserId(null));
    }

    @Test
    void rejectsAnElementAbove100Characters() {
        String body = ExampleFiles.read("ingest-invalid-C4-element-length.json");

        IngestException error = assertThrows(IngestException.class, () -> IngestPipeline.process(body, Clock.systemUTC()));

        assertEquals(IngestException.Reason.ELEMENT_LENGTH, error.reason());
    }

    @Test
    void rejectsAnElementWithASpace() {
        String body = ExampleFiles.read("ingest-invalid-C4-element-pattern.json");

        IngestException error = assertThrows(IngestException.class, () -> IngestPipeline.process(body, Clock.systemUTC()));

        assertEquals(IngestException.Reason.ELEMENT_PATTERN, error.reason());
    }

    @Test
    void rejectsASessionIdOfTheWrongLength() {
        IngestException error = assertThrows(IngestException.class,
                () -> EventFieldValidator.validateSessionId("3fa85f64-5717-4562-b3fc-2c963f66afa"));

        assertEquals(IngestException.Reason.SESSION_ID_NOT_UUID, error.reason());
    }

    @Test
    void rejectsASessionIdThatIsNotAUuid() {
        String body = ExampleFiles.read("ingest-invalid-C5-session-id.json");

        IngestException error = assertThrows(IngestException.class, () -> IngestPipeline.process(body, Clock.systemUTC()));

        assertEquals(IngestException.Reason.SESSION_ID_NOT_UUID, error.reason());
    }

    @Test
    void rejectsANegativeAgeMs() {
        String body = ExampleFiles.read("ingest-invalid-C15-negative-age.json");

        IngestException error = assertThrows(IngestException.class, () -> IngestPipeline.process(body, Clock.systemUTC()));

        assertEquals(IngestException.Reason.NEGATIVE_AGE_MS, error.reason());
    }

    @Test
    void acceptsAnElementOfExactly100Characters() {
        String element = "a".repeat(100);

        assertDoesNotThrow(() -> EventFieldValidator.validateElement(element));
    }

    @Test
    void rejectsAnElementOfZeroCharacters() {
        IngestException error = assertThrows(IngestException.class, () -> EventFieldValidator.validateElement(""));

        assertEquals(IngestException.Reason.ELEMENT_LENGTH, error.reason());
    }

    @Test
    void rejectsAUserIdOfZeroCharacters() {
        IngestException error = assertThrows(IngestException.class, () -> EventFieldValidator.validateUserId(""));

        assertEquals(IngestException.Reason.USER_ID_LENGTH, error.reason());
    }

    @Test
    void acceptsAUserIdOf254Characters() {
        String userId = "u".repeat(254);

        assertDoesNotThrow(() -> EventFieldValidator.validateUserId(userId));
    }

    @Test
    void rejectsAUserIdOf255Characters() {
        String userId = "u".repeat(255);

        IngestException error = assertThrows(IngestException.class, () -> EventFieldValidator.validateUserId(userId));

        assertEquals(IngestException.Reason.USER_ID_LENGTH, error.reason());
    }

    @Test
    void rejectsAUserIdWithAControlCharacter() {
        String userId = "user-1\n42";

        IngestException error = assertThrows(IngestException.class, () -> EventFieldValidator.validateUserId(userId));

        assertEquals(IngestException.Reason.USER_ID_CHARACTER, error.reason());
    }

    @Test
    void rejectsAUserIdWithTheDeleteCharacter() {
        String userId = "user-1" + (char) 0x7f;

        IngestException error = assertThrows(IngestException.class, () -> EventFieldValidator.validateUserId(userId));

        assertEquals(IngestException.Reason.USER_ID_CHARACTER, error.reason());
    }

    @Test
    void acceptsAUserIdWithThePrintableBoundaryCharacters() {
        String userId = "user-1 " + (char) 0x20 + (char) 0x7e;

        assertDoesNotThrow(() -> EventFieldValidator.validateUserId(userId));
    }
}
