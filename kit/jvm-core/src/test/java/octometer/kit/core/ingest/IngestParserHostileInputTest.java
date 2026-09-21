package octometer.kit.core.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests with a hostile ingest body. The parser must reject deep nesting, a
 * very long string, and a very large number, and it must not throw a
 * {@link StackOverflowError} and must not run for a long time.
 */
class IngestParserHostileInputTest {

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void rejectsDeepNestingWithoutAStackOverflow() {
        // The nested value sits in an ignored field (rule C32), thus the
        // parser must skip it and still stop at the depth limit.
        StringBuilder body = new StringBuilder();
        body.append("{\"sessionId\": \"3fa85f64-5717-4562-b3fc-2c963f66afa6\", \"clicks\": [], \"padding\": ");
        int depth = 5000;
        body.append("[".repeat(depth));
        body.append("]".repeat(depth));
        body.append("}");

        assertThrows(IngestException.class, () -> IngestParser.parse(body.toString()));
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void rejectsAVeryLongElementValueByTheBodySizeLimit() {
        // A single field near the body cap must fail fast on the size
        // check, before the parser reads a single JSON token.
        String longValue = "a".repeat(20_000);
        String body = "{\"sessionId\": \"3fa85f64-5717-4562-b3fc-2c963f66afa6\", \"clicks\": [{\"element\": \""
                + longValue + "\", \"ageMs\": 100}]}";

        IngestException error = assertThrows(IngestException.class, () -> IngestParser.parse(body));

        assertEquals(IngestException.Reason.BODY_TOO_LARGE, error.reason());
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void ignoresALongPaddingStringWithinTheBodyLimit() {
        // A long, but in-budget, unknown string field must not slow the
        // parser down and must not change the parsed result.
        String padding = "x".repeat(15_000);
        String body = "{\"sessionId\": \"3fa85f64-5717-4562-b3fc-2c963f66afa6\", \"padding\": \""
                + padding + "\", \"clicks\": [{\"element\": \"checkout.save\", \"ageMs\": 100}]}";

        ParsedIngestRequest request = assertDoesNotThrow(() -> IngestParser.parse(body));

        assertEquals(1, request.clicks().size());
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void rejectsAVeryLargeAgeMsNumber() {
        String hugeNumber = "9".repeat(5000);
        String body = "{\"sessionId\": \"3fa85f64-5717-4562-b3fc-2c963f66afa6\", \"clicks\": [{\"element\": \"checkout.save\", \"ageMs\": "
                + hugeNumber + "}]}";

        IngestException error = assertThrows(IngestException.class, () -> IngestParser.parse(body));

        assertEquals(IngestException.Reason.NUMBER_TOO_LARGE, error.reason());
    }
}
