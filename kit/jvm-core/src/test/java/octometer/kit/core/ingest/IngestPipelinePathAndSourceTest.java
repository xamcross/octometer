package octometer.kit.core.ingest;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of {@link IngestPipeline#process} for the fields of version 1.1 of
 * the contract: `path` (rule C39), `referrerHost` (rule C40), the drop
 * rule for an invalid value (rule C41), and the reserved element prefix
 * (rule C38, issue #103).
 *
 * <p>This class does not match a `path` value against a route pattern
 * list; issue #104 owns that (rule C42). Each test here checks only the
 * shape check of rule C39, so {@link IngestEvent#path()} here holds the
 * checked client value, not a match result.
 */
class IngestPipelinePathAndSourceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-21T10:15:30.000Z"), ZoneOffset.UTC);

    @Test
    void aValidPathOnAClickEntryReachesTheEventRecord() {
        String body = ExampleFiles.read("ingest-valid-C39-path.json");

        List<IngestEvent> events = IngestPipeline.process(body, FIXED_CLOCK);

        assertEquals(1, events.size());
        assertEquals("/articles/example-article", events.get(0).path());
    }

    @Test
    void anInvalidPathIsDroppedAndTheEntryStays() {
        String body = ExampleFiles.read("ingest-valid-C41-bad-path-dropped.json");

        List<IngestEvent> events = IngestPipeline.process(body, FIXED_CLOCK);

        assertEquals(1, events.size());
        assertEquals("article.read-more", events.get(0).element());
        assertNull(events.get(0).path());
    }

    @Test
    void aPathOf151BytesIsDropped() {
        String body = bodyWithOneClickField("path", "\"" + "/" + "a".repeat(150) + "\"");

        List<IngestEvent> events = IngestPipeline.process(body, FIXED_CLOCK);

        assertEquals(1, events.size());
        assertNull(events.get(0).path());
    }

    @Test
    void aPathThatStartsWithTwoSlashesIsDropped() {
        String body = bodyWithOneClickField("path", "\"//articles/example\"");

        List<IngestEvent> events = IngestPipeline.process(body, FIXED_CLOCK);

        assertNull(events.get(0).path());
    }

    @Test
    void aPathWithAQuestionMarkIsDropped() {
        String body = bodyWithOneClickField("path", "\"/articles?id=1\"");

        List<IngestEvent> events = IngestPipeline.process(body, FIXED_CLOCK);

        assertNull(events.get(0).path());
    }

    @Test
    void aPathWithAFragmentIsDropped() {
        String body = bodyWithOneClickField("path", "\"/articles#top\"");

        List<IngestEvent> events = IngestPipeline.process(body, FIXED_CLOCK);

        assertNull(events.get(0).path());
    }

    @Test
    void aPathWithAPartialEscapeIsDropped() {
        String body = bodyWithOneClickField("path", "\"/articles/caf%C\"");

        List<IngestEvent> events = IngestPipeline.process(body, FIXED_CLOCK);

        assertNull(events.get(0).path());
    }

    @Test
    void theReferrerHostOfTheOtherLiteralReachesTheEventRecord() {
        String body = sessionStartBody("\"other\"");

        List<IngestEvent> events = IngestPipeline.process(body, FIXED_CLOCK);

        assertEquals("other", events.get(0).referrerHost());
    }

    @Test
    void aReferrerHostOfTheSourceListReachesTheEventRecord() {
        String body = sessionStartBody("\"google.com\"");

        List<IngestEvent> events = IngestPipeline.process(body, FIXED_CLOCK);

        assertEquals("google.com", events.get(0).referrerHost());
    }

    @Test
    void aReferrerHostOutsideTheListWithAHostNameFormBecomesOther() {
        String body = sessionStartBody("\"example.org\"");

        List<IngestEvent> events = IngestPipeline.process(body, FIXED_CLOCK);

        assertEquals("other", events.get(0).referrerHost());
    }

    @Test
    void aReferrerHostWithADifferentFormIsDropped() {
        String body = sessionStartBody("\"Example.org\"");

        List<IngestEvent> events = IngestPipeline.process(body, FIXED_CLOCK);

        assertEquals(1, events.size());
        assertNull(events.get(0).referrerHost());
    }

    @Test
    void aReferrerHostOnAClickEntryDoesNotReachTheEventRecord() {
        String body = bodyWithOneClickField("referrerHost", "\"google.com\"");

        List<IngestEvent> events = IngestPipeline.process(body, FIXED_CLOCK);

        assertEquals(1, events.size());
        assertNull(events.get(0).referrerHost());
    }

    @Test
    void anEntryWithTheExactSessionStartElementIsKept() {
        String body = sessionStartBody("\"other\"");

        List<IngestEvent> events = IngestPipeline.process(body, FIXED_CLOCK);

        assertEquals(1, events.size());
        assertEquals("octo:session-start", events.get(0).element());
    }

    @Test
    void anEntryWithAReservedPrefixThatIsNotSessionStartIsDroppedAndOthersStay() {
        String body = """
                {"sessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                 "clicks": [
                   {"element": "octo:foo", "ageMs": 100},
                   {"element": "checkout.save", "ageMs": 200}
                 ]}
                """;

        List<IngestEvent> events = IngestPipeline.process(body, FIXED_CLOCK);

        assertEquals(1, events.size());
        assertEquals("checkout.save", events.get(0).element());
    }

    @Test
    void anUpperCaseReservedPrefixIsAlsoDropped() {
        String body = """
                {"sessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                 "clicks": [
                   {"element": "OCTO:foo", "ageMs": 100},
                   {"element": "checkout.save", "ageMs": 200}
                 ]}
                """;

        List<IngestEvent> events = IngestPipeline.process(body, FIXED_CLOCK);

        assertEquals(1, events.size());
        assertEquals("checkout.save", events.get(0).element());
    }

    @Test
    void onlyTheExactLowerCaseTextMarksTheSessionStart() {
        String body = """
                {"sessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                 "clicks": [
                   {"element": "OCTO:SESSION-START", "ageMs": 0},
                   {"element": "checkout.save", "ageMs": 200}
                 ]}
                """;

        List<IngestEvent> events = IngestPipeline.process(body, FIXED_CLOCK);

        assertEquals(1, events.size());
        assertEquals("checkout.save", events.get(0).element());
    }

    @Test
    void aBatchWithThreeInvalidPathValuesWritesAtMostOneWarning() {
        CapturingLoggerFinder.clear();
        String body = """
                {"sessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                 "clicks": [
                   {"element": "a.one", "ageMs": 1, "path": "//one"},
                   {"element": "a.two", "ageMs": 1, "path": "/two?x=1"},
                   {"element": "a.three", "ageMs": 1, "path": "/three#top"}
                 ]}
                """;

        List<IngestEvent> events = IngestPipeline.process(body, FIXED_CLOCK);

        assertEquals(3, events.size());
        assertTrue(events.stream().allMatch(event -> event.path() == null));
        assertEquals(1, CapturingLoggerFinder.messages().size());
    }

    @Test
    void theResponseStaysValidForABatchWithInvalidValues() {
        // The acceptance criterion asks for status 204 for such a batch.
        // This module has no HTTP layer, so this test asserts the one
        // fact that this module owns: process() throws nothing, and it
        // keeps every entry.
        String body = """
                {"sessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                 "clicks": [
                   {"element": "a.one", "ageMs": 1, "path": "//one"},
                   {"element": "octo:session-start", "ageMs": 0, "referrerHost": "Bad Host"},
                   {"element": "a.two", "ageMs": 1}
                 ]}
                """;

        List<IngestEvent> events = IngestPipeline.process(body, FIXED_CLOCK);

        assertEquals(3, events.size());
    }

    @Test
    void noLogLineHoldsARawPathOrARawHost() {
        CapturingLoggerFinder.clear();
        String body = """
                {"sessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                 "clicks": [
                   {"element": "octo:session-start", "ageMs": 0, "referrerHost": "Attacker.example"},
                   {"element": "a.one", "ageMs": 1, "path": "//one"},
                   {"element": "octo:foo", "ageMs": 1}
                 ]}
                """;

        IngestPipeline.process(body, FIXED_CLOCK);

        for (String message : CapturingLoggerFinder.messages()) {
            assertFalse(message.contains("Attacker.example"), "a message must not hold a raw host");
            assertFalse(message.contains("//one"), "a message must not hold a raw path");
        }
    }

    private static String sessionStartBody(String referrerHostJsonValue) {
        return "{\"sessionId\": \"3fa85f64-5717-4562-b3fc-2c963f66afa6\","
                + " \"clicks\": [{\"element\": \"octo:session-start\", \"ageMs\": 0, "
                + "\"referrerHost\": " + referrerHostJsonValue + "}]}";
    }

    private static String bodyWithOneClickField(String fieldName, String jsonValue) {
        return "{\"sessionId\": \"3fa85f64-5717-4562-b3fc-2c963f66afa6\","
                + " \"clicks\": [{\"element\": \"checkout.save\", \"ageMs\": 1, "
                + "\"" + fieldName + "\": " + jsonValue + "}]}";
    }
}
