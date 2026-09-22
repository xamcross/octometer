package octometer.kit.core.ingest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import octometer.kit.core.path.PathPatternMatcher;
import octometer.kit.core.store.DeletionResult;
import octometer.kit.core.store.EventLogStore;
import octometer.kit.core.store.StoredEvent;
import octometer.kit.core.user.UserIdResolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the route pattern match of contract rule C42 inside {@link
 * IngestPipeline} (issue #104). {@link IngestSettings} carries the
 * matcher; the pipeline never reads {@code OCTOMETER_PATH_PATTERNS} on
 * its own (design decision D40).
 */
class IngestPipelinePathMatchTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-21T10:15:30.000Z"), ZoneOffset.UTC);

    private static final List<String> SAMPLE_PATTERNS =
            List.of("/", "/articles", "/articles/*", "/history/:id", "/ovdp/rates");

    @Test
    void aValidClientPathBecomesTheMatchResult() {
        IngestSettings settings = new IngestSettings(false, PathPatternMatcher.of(SAMPLE_PATTERNS));
        String body = bodyWithPath("/history/42");

        List<IngestEvent> events = IngestPipeline.process(body, FIXED_CLOCK, settings);

        assertEquals("/history/:id", events.get(0).path());
    }

    @Test
    void aClientPathWithNoMatchStoresOther() {
        IngestSettings settings = new IngestSettings(false, PathPatternMatcher.of(SAMPLE_PATTERNS));
        String body = bodyWithPath("/tokens/abc");

        List<IngestEvent> events = IngestPipeline.process(body, FIXED_CLOCK, settings);

        assertEquals("/other", events.get(0).path());
    }

    @Test
    void withNoPathPatternMatcherTheEventHoldsNoPath() {
        IngestSettings settings = new IngestSettings(false, null);
        String body = bodyWithPath("/history/42");

        List<IngestEvent> events = IngestPipeline.process(body, FIXED_CLOCK, settings);

        assertNull(events.get(0).path());
    }

    @Test
    void anInvalidShapedPathStillGivesNoPathEvenWithAMatcher() {
        IngestSettings settings = new IngestSettings(false, PathPatternMatcher.of(SAMPLE_PATTERNS));
        String body = bodyWithPath("//two-slashes");

        List<IngestEvent> events = IngestPipeline.process(body, FIXED_CLOCK, settings);

        assertNull(events.get(0).path());
    }

    @Test
    void aClickWithNoPathFieldGivesNoPath() {
        IngestSettings settings = new IngestSettings(false, PathPatternMatcher.of(SAMPLE_PATTERNS));
        String body = """
                {"sessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                 "clicks": [{"element": "checkout.save", "ageMs": 1}]}
                """;

        List<IngestEvent> events = IngestPipeline.process(body, FIXED_CLOCK, settings);

        assertNull(events.get(0).path());
    }

    /**
     * The criterion of the maintainer's correction of 2026-09-22 (pull
     * request #131 reviews): the pipeline sets the component `path` to
     * the match result before {@link EventLogStore#append}. A test store
     * sees `/history/:id`, and no component of the recorded event holds
     * `/history/42`.
     */
    @Test
    void theStoreReceivesTheMatchResultAndNeverTheRawClientPath() {
        IngestSettings settings = new IngestSettings(false, PathPatternMatcher.of(SAMPLE_PATTERNS));
        String body = bodyWithPath("/history/42");
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

        IngestPipeline.ingest(body, FIXED_CLOCK, resolver, testStore, settings);

        assertEquals(1, recordedEvents.size());
        IngestEvent recordedEvent = recordedEvents.get(0);
        assertEquals("/history/:id", recordedEvent.path());
        assertFalse(recordedEvent.toString().contains("/history/42"),
                "the event text must not hold the raw client path");
    }

    @Test
    void theInMemoryStoreHoldsTheMatchResultAndNeverTheRawClientPath() {
        IngestSettings settings = new IngestSettings(false, PathPatternMatcher.of(SAMPLE_PATTERNS));
        String body = bodyWithPath("/history/42");
        octometer.kit.core.store.InMemoryEventLogStore store = new octometer.kit.core.store.InMemoryEventLogStore();
        UserIdResolver resolver = () -> "user-1";

        IngestPipeline.ingest(body, FIXED_CLOCK, resolver, store, settings);

        List<StoredEvent> stored = store.events();
        assertEquals(1, stored.size());
        assertEquals("/history/:id", stored.get(0).path());
        for (StoredEvent event : stored) {
            assertFalse(event.toString().contains("/history/42"));
        }
    }

    @Test
    void aBatchWithAnInvalidShapedPathAndAMatcherStillWritesAtMostOneWarning() {
        CapturingLoggerFinder.clear();
        IngestSettings settings = new IngestSettings(false, PathPatternMatcher.of(SAMPLE_PATTERNS));
        String body = """
                {"sessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                 "clicks": [
                   {"element": "a.one", "ageMs": 1, "path": "//one"},
                   {"element": "a.two", "ageMs": 1, "path": "//two"}
                 ]}
                """;

        List<IngestEvent> events = IngestPipeline.process(body, FIXED_CLOCK, settings);

        assertTrue(events.stream().allMatch(event -> event.path() == null));
        assertEquals(1, CapturingLoggerFinder.messages().size());
    }

    private static String bodyWithPath(String path) {
        return "{\"sessionId\": \"3fa85f64-5717-4562-b3fc-2c963f66afa6\","
                + " \"clicks\": [{\"element\": \"checkout.save\", \"ageMs\": 1, \"path\": \"" + path + "\"}]}";
    }
}
