package octometer.kit.core.ingest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests of a duplicate key in one JSON object (rule C36). The maintainer
 * decision: a duplicate key makes the whole body invalid, for a known
 * key and for an unknown key.
 */
class IngestParserDuplicateKeyTest {

    @Test
    void rejectsADuplicateSessionIdKey() {
        String body = "{\"sessionId\": \"3fa85f64-5717-4562-b3fc-2c963f66afa6\", "
                + "\"sessionId\": \"3fa85f64-5717-4562-b3fc-2c963f66afa6\", "
                + "\"clicks\": [{\"element\": \"checkout.save\", \"ageMs\": 100}]}";

        IngestException error = assertThrows(IngestException.class, () -> IngestParser.parse(body));

        assertEquals(IngestException.Reason.DUPLICATE_FIELD, error.reason());
    }

    @Test
    void rejectsADuplicateClicksKeyEvenWhenTheSecondArrayBreaksTheBatchLimit() {
        // The first clicks array is empty and legal on its own. The
        // second array holds 51 entries, above the limit of rule C17.
        // A working duplicate-key check must reject this body before it
        // reads that second array, thus rule C17 stays enforced too.
        StringBuilder secondClicks = new StringBuilder("[");
        for (int i = 0; i < 51; i++) {
            if (i > 0) {
                secondClicks.append(",");
            }
            secondClicks.append("{\"element\": \"checkout.save\", \"ageMs\": 1}");
        }
        secondClicks.append("]");
        String body = "{\"sessionId\": \"3fa85f64-5717-4562-b3fc-2c963f66afa6\", \"clicks\": [], "
                + "\"clicks\": " + secondClicks + "}";

        IngestException error = assertThrows(IngestException.class, () -> IngestParser.parse(body));

        assertEquals(IngestException.Reason.DUPLICATE_FIELD, error.reason());
    }

    @Test
    void rejectsADuplicateAgeMsKeyInOneClick() {
        String body = "{\"sessionId\": \"3fa85f64-5717-4562-b3fc-2c963f66afa6\", \"clicks\": "
                + "[{\"element\": \"checkout.save\", \"ageMs\": 1, \"ageMs\": 2}]}";

        IngestException error = assertThrows(IngestException.class, () -> IngestParser.parse(body));

        assertEquals(IngestException.Reason.DUPLICATE_FIELD, error.reason());
    }

    @Test
    void rejectsADuplicateKeyInsideAnUnknownField() {
        String body = "{\"sessionId\": \"3fa85f64-5717-4562-b3fc-2c963f66afa6\", \"clicks\": [], "
                + "\"extra\": {\"a\": 1, \"a\": 2}}";

        IngestException error = assertThrows(IngestException.class, () -> IngestParser.parse(body));

        assertEquals(IngestException.Reason.DUPLICATE_FIELD, error.reason());
    }
}
