package octometer.kit.core.ingest;

import octometer.kit.core.store.EventLogStore;
import octometer.kit.core.store.InMemoryEventLogStore;
import octometer.kit.core.store.StoredEvent;
import octometer.kit.core.user.UserIdResolver;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of {@link IngestPipeline#ingest} against the acceptance criteria of
 * issue #26. The user id comes only from a {@link UserIdResolver}; a
 * `userId` field in the request body never reaches the store.
 */
class IngestPipelineIngestTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-21T10:15:30.000Z"), ZoneOffset.UTC);

    @Test
    void aUserIdFieldInTheBodyDoesNotChangeTheStoredUserId() {
        String body = """
                {"sessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                 "userId": "body-user",
                 "clicks": [{"element": "checkout.save", "ageMs": 1200}]}
                """;
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        UserIdResolver resolver = () -> "resolver-user";

        IngestPipeline.ingest(body, FIXED_CLOCK, resolver, store, new IngestSettings(false));

        List<StoredEvent> events = store.events();
        assertEquals(1, events.size());
        assertEquals("resolver-user", events.get(0).userId());
    }

    @Test
    void withoutAUserIdAndWithTheFlagOffTheStoreGetsZeroEvents() {
        String body = """
                {"sessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                 "clicks": [{"element": "checkout.save", "ageMs": 1200}]}
                """;
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        UserIdResolver resolver = () -> null;

        IngestPipeline.ingest(body, FIXED_CLOCK, resolver, store, new IngestSettings(false));

        assertTrue(store.events().isEmpty());
    }

    @Test
    void withoutAUserIdAndWithTheFlagOnTheStoreGetsTheEventWithUserIdNull() {
        String body = """
                {"sessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                 "clicks": [{"element": "checkout.save", "ageMs": 1200}]}
                """;
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        UserIdResolver resolver = () -> null;

        IngestPipeline.ingest(body, FIXED_CLOCK, resolver, store, new IngestSettings(true));

        List<StoredEvent> events = store.events();
        assertEquals(1, events.size());
        assertNull(events.get(0).userId());
    }

    @Test
    void anEmptyResolvedUserIdMakesTheRequestInvalid() {
        String body = """
                {"sessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                 "clicks": [{"element": "checkout.save", "ageMs": 1200}]}
                """;
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        UserIdResolver resolver = () -> "";

        // A bad value from the app's own resolver is a defect of the app,
        // not of the client (design decision D18). This method throws
        // IllegalStateException, never IngestException, so an adapter
        // never returns 400 for a valid body.
        assertThrows(IllegalStateException.class,
                () -> IngestPipeline.ingest(body, FIXED_CLOCK, resolver, store, new IngestSettings(true)));

        assertTrue(store.events().isEmpty());
    }

    @Test
    void aResolvedUserIdOf255CharactersMakesTheRequestInvalid() {
        String body = """
                {"sessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                 "clicks": [{"element": "checkout.save", "ageMs": 1200}]}
                """;
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        UserIdResolver resolver = () -> "u".repeat(255);

        assertThrows(IllegalStateException.class,
                () -> IngestPipeline.ingest(body, FIXED_CLOCK, resolver, store, new IngestSettings(true)));

        assertTrue(store.events().isEmpty());
    }

    @Test
    void aUserIdFieldInsideAClickEntryDoesNotChangeTheStoredUserId() {
        String body = """
                {"sessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                 "clicks": [{"element": "checkout.save", "ageMs": 1200, "userId": "click-user"}]}
                """;
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        UserIdResolver resolver = () -> "resolver-user";

        IngestPipeline.ingest(body, FIXED_CLOCK, resolver, store, new IngestSettings(false));

        assertEquals("resolver-user", store.events().get(0).userId());
    }

    @Test
    void aUserIdFieldWithADifferentLetterCaseDoesNotChangeTheStoredUserId() {
        String body = """
                {"sessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                 "UserId": "body-user",
                 "clicks": [{"element": "checkout.save", "ageMs": 1200}]}
                """;
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        UserIdResolver resolver = () -> "resolver-user";

        IngestPipeline.ingest(body, FIXED_CLOCK, resolver, store, new IngestSettings(false));

        assertEquals("resolver-user", store.events().get(0).userId());
    }

    @Test
    void anEmptyClicksArrayGivesZeroCallsOfAppend() {
        String body = """
                {"sessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                 "clicks": []}
                """;
        AtomicInteger callCount = new AtomicInteger();
        EventLogStore store = new EventLogStore() {
            @Override
            public void append(List<IngestEvent> events, String userId) {
                callCount.incrementAndGet();
            }

            @Override
            public void deleteByUserId(String userId) {
                throw new UnsupportedOperationException();
            }
        };
        UserIdResolver resolver = () -> "user-1";

        IngestPipeline.ingest(body, FIXED_CLOCK, resolver, store, new IngestSettings(false));

        assertEquals(0, callCount.get());
    }

    @Test
    void ingestGivesTheWholeBatchToAppendInOneCall() {
        String body = ExampleFiles.read("ingest-valid-C13.json");
        AtomicInteger callCount = new AtomicInteger();
        EventLogStore store = new EventLogStore() {
            @Override
            public void append(List<IngestEvent> events, String userId) {
                callCount.incrementAndGet();
                assertEquals(2, events.size());
            }

            @Override
            public void deleteByUserId(String userId) {
                throw new UnsupportedOperationException();
            }
        };
        UserIdResolver resolver = () -> "user-1";

        IngestPipeline.ingest(body, FIXED_CLOCK, resolver, store, new IngestSettings(false));

        assertEquals(1, callCount.get());
    }
}
