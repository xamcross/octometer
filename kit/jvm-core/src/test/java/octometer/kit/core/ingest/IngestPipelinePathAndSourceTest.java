package octometer.kit.core.ingest;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import octometer.kit.core.store.DeletionResult;
import octometer.kit.core.store.EventLogStore;
import octometer.kit.core.user.UserIdResolver;

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
 * shape check of rule C39, so {@link IngestEvent#path()} is always
 * {@code null} in this class, also for a valid client value.
 */
class IngestPipelinePathAndSourceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-21T10:15:30.000Z"), ZoneOffset.UTC);

    @Test
    void aValidPathPassesTheShapeCheckButDoesNotReachTheEventRecord() {
        // Rule C39 checks the shape of a valid path with no fatal error.
        // Rule C42 says the server stores only a route pattern match,
        // and this module has no route pattern list yet. The event
        // record then holds a null path, also for a valid client value.
        // Issue #104 adds the match and fills this component.
        String body = ExampleFiles.read("ingest-valid-C39-path.json");

        List<IngestEvent> events = IngestPipeline.process(body, FIXED_CLOCK);

        assertEquals(1, events.size());
        assertNull(events.get(0).path());
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

    /**
     * MINOR 2 of the first Java review of pull request #131: no test
     * capped the rule C38 warning at one for each batch. This test adds
     * that missing coverage, with the same shape as
     * {@link #aBatchWithThreeInvalidPathValuesWritesAtMostOneWarning()}.
     */
    @Test
    void aBatchWithThreeReservedElementsWritesAtMostOneWarning() {
        CapturingLoggerFinder.clear();
        String body = """
                {"sessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                 "clicks": [
                   {"element": "octo:one", "ageMs": 1},
                   {"element": "octo:two", "ageMs": 1},
                   {"element": "octo:three", "ageMs": 1},
                   {"element": "checkout.save", "ageMs": 1}
                 ]}
                """;

        List<IngestEvent> events = IngestPipeline.process(body, FIXED_CLOCK);

        assertEquals(1, events.size());
        assertEquals("checkout.save", events.get(0).element());
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

    /**
     * The new criterion of the maintainer's correction of 2026-09-22, on
     * pull request #131. A test store records each event of {@link
     * IngestPipeline#ingest}. For a request with a valid `path`, no
     * component of the recorded event, and no {@code toString()} text of
     * the event or of the parsed click, holds the client value. This
     * proves rule C39 ("the server never stores the raw client value")
     * at the seam that an app store reads.
     */
    @Test
    void aStoreNeverReceivesTheRawClientPathForAValidPath() {
        String rawPath = "/articles/example-article";
        String body = ExampleFiles.read("ingest-valid-C39-path.json");
        List<IngestEvent> recordedEvents = new ArrayList<>();
        EventLogStore testStore = new EventLogStore() {
            @Override
            public void append(List<IngestEvent> events, String userId) {
                recordedEvents.addAll(events);
            }

            @Override
            public DeletionResult deleteByUserId(String userId) {
                throw new UnsupportedOperationException();
            }
        };
        UserIdResolver resolver = () -> "user-1";

        IngestPipeline.ingest(body, FIXED_CLOCK, resolver, testStore, new IngestSettings(false));

        assertEquals(1, recordedEvents.size());
        IngestEvent recordedEvent = recordedEvents.get(0);
        assertNull(recordedEvent.path(), "the event record must not hold the client path");
        assertFalse(recordedEvent.toString().contains(rawPath),
                "the event text must not hold the client path");

        ParsedIngestRequest parsed = IngestParser.parse(body);
        assertFalse(parsed.toString().contains(rawPath),
                "the parsed request text must not hold the client path");
        assertFalse(parsed.clicks().get(0).toString().contains(rawPath),
                "the parsed click text must not hold the client path");
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
