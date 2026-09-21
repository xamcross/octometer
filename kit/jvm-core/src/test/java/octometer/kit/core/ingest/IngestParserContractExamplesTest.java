package octometer.kit.core.ingest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests with the ingest body examples of `contract/examples/` (issue #8).
 * Each valid file must parse. Each invalid file must fail with the rule
 * that its file name holds (rules C13, C15, C17, C18, C36, C37).
 *
 * <p>Version 1.1 adds the fields `path` and `referrerHost` (rules C39 to
 * C41, issue #101). The present parser does not check these fields, so it
 * skips each one as an unknown field of a `clicks` entry (rule C32). This
 * is the forward-compatible behavior of an old kit: it drops a new field
 * and it does not fail. Issue #103 adds the checks of C39 to C41.
 */
class IngestParserContractExamplesTest {

    @Test
    void parsesTheValidIngestBody() {
        String body = ExampleFiles.read("ingest-valid-C13.json");

        ParsedIngestRequest request = IngestParser.parse(body);

        assertEquals("3fa85f64-5717-4562-b3fc-2c963f66afa6", request.sessionId());
        assertEquals(2, request.clicks().size());
        assertEquals("checkout.save", request.clicks().get(0).element());
        assertEquals(1200L, request.clicks().get(0).ageMs());
        assertEquals("nav.menu.open", request.clicks().get(1).element());
        assertEquals(400L, request.clicks().get(1).ageMs());
    }

    @Test
    void acceptsABatchOfExactly50Clicks() {
        StringBuilder clicks = new StringBuilder("[");
        for (int i = 0; i < 50; i++) {
            if (i > 0) {
                clicks.append(",");
            }
            clicks.append("{\"element\": \"checkout.save\", \"ageMs\": 1}");
        }
        clicks.append("]");
        String body = "{\"sessionId\": \"3fa85f64-5717-4562-b3fc-2c963f66afa6\", \"clicks\": " + clicks + "}";

        ParsedIngestRequest request = IngestParser.parse(body);

        assertEquals(50, request.clicks().size());
    }

    @Test
    void rejectsABodyWithNoSessionId() {
        String body = ExampleFiles.read("ingest-invalid-C13-missing-sessionid.json");

        IngestException error = assertThrows(IngestException.class, () -> IngestParser.parse(body));

        assertEquals(IngestException.Reason.MISSING_FIELD, error.reason());
    }

    @Test
    void rejectsABodyWithNoClicks() {
        String body = ExampleFiles.read("ingest-invalid-C13-missing-clicks.json");

        IngestException error = assertThrows(IngestException.class, () -> IngestParser.parse(body));

        assertEquals(IngestException.Reason.MISSING_FIELD, error.reason());
    }

    @Test
    void rejectsABatchAbove50Clicks() {
        String body = ExampleFiles.read("ingest-invalid-C17-batch-limit.json");

        IngestException error = assertThrows(IngestException.class, () -> IngestParser.parse(body));

        assertEquals(IngestException.Reason.TOO_MANY_CLICKS, error.reason());
    }

    @Test
    void rejectsABodyAbove16Kb() {
        String body = ExampleFiles.read("ingest-invalid-C18-body-size.json");

        IngestException error = assertThrows(IngestException.class, () -> IngestParser.parse(body));

        assertEquals(IngestException.Reason.BODY_TOO_LARGE, error.reason());
    }

    @Test
    void ignoresAnUnknownFieldInTheBody() {
        String body = "{\"sessionId\": \"3fa85f64-5717-4562-b3fc-2c963f66afa6\","
                + " \"clicks\": [{\"element\": \"checkout.save\", \"ageMs\": 1200}],"
                + " \"userId\": \"client-supplied-and-ignored\"}";

        ParsedIngestRequest request = IngestParser.parse(body);

        assertEquals(1, request.clicks().size());
    }

    @Test
    void rejectsAnUnknownTopLevelType() {
        String body = "[1, 2, 3]";

        IngestException error = assertThrows(IngestException.class, () -> IngestParser.parse(body));

        assertEquals(IngestException.Reason.WRONG_TOP_LEVEL_TYPE, error.reason());
    }

    @Test
    void rejectsABodyWithADuplicateKey() {
        String body = ExampleFiles.read("ingest-invalid-C36-duplicate-key.json");

        IngestException error = assertThrows(IngestException.class, () -> IngestParser.parse(body));

        assertEquals(IngestException.Reason.DUPLICATE_FIELD, error.reason());
    }

    @Test
    void rejectsAnAgeMsWithAFraction() {
        String body = ExampleFiles.read("ingest-invalid-C37-agems-fraction.json");

        IngestException error = assertThrows(IngestException.class, () -> IngestParser.parse(body));

        assertEquals(IngestException.Reason.WRONG_FIELD_TYPE, error.reason());
    }

    @Test
    void parsesAClickWithAPathField() {
        String body = ExampleFiles.read("ingest-valid-C39-path.json");

        ParsedIngestRequest request = IngestParser.parse(body);

        assertEquals(1, request.clicks().size());
        assertEquals("article.read-more", request.clicks().get(0).element());
    }

    @Test
    void parsesAClickWithABadPathAsAnUnknownField() {
        String body = ExampleFiles.read("ingest-valid-C41-bad-path-dropped.json");

        ParsedIngestRequest request = IngestParser.parse(body);

        assertEquals(1, request.clicks().size());
        assertEquals("article.read-more", request.clicks().get(0).element());
    }
}
