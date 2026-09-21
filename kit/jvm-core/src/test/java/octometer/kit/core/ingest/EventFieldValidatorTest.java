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

        assertDoesNotThrow(() -> EventFieldValidator.validateElement("checkout.save"));
        assertDoesNotThrow(() -> EventFieldValidator.validateSessionId("0b0e4e0e-6a55-4c1e-9a53-0c1f6f7a2d11"));
        assertTrue(json.contains("\"checkout.save\""));
        assertTrue(json.contains("\"0b0e4e0e-6a55-4c1e-9a53-0c1f6f7a2d11\""));
    }

    @Test
    void acceptsTheNullUserIdOfTheEventExample() {
        String json = ExampleFiles.read("event-userid-null-C6.json");

        assertTrue(json.contains("\"userId\": null"));
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
}
