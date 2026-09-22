package octometer.kit.mongo.store;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import octometer.kit.core.ingest.IngestEvent;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the event cap of design decision D21 and issue #34, against a
 * real MongoDB server. Each test needs Docker; a machine with no Docker
 * skips the whole class.
 */
@Testcontainers(disabledWithoutDocker = true)
class MongoEventLogStoreEventCapTest {

    private static final Instant START = Instant.parse("2026-09-22T10:00:00.000Z");

    @Container
    private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private MongoClient client;
    private MongoDatabase database;

    @BeforeEach
    void openClient() {
        client = MongoClients.create(MONGO.getConnectionString());
        database = client.getDatabase("octometer_test_" + System.nanoTime());
    }

    @AfterEach
    void closeClient() {
        client.close();
    }

    @Test
    void aBatchAboveTheCapIsDroppedAndTheStoreWritesOneWarning() {
        CapturingLoggerFinder.clear();
        MutableClock clock = new MutableClock(START);
        // maxEvents 1: the first batch fills the cap, so the second batch
        // must find the cache at or above the cap once it refreshes.
        MongoEventLogStore store = new MongoEventLogStore(database, 30, 1, clock);

        store.append(List.of(new IngestEvent("s1", "e1", Instant.now())), "user-1");
        assertEquals(1, rawCollection().countDocuments());

        // Force the guard to read a fresh count: the previous append
        // already cached the pre-insert count of zero, so a second
        // append inside the same refresh interval would still pass (see
        // the overshoot test below). Advancing the clock forces a fresh
        // read that now sees the one document above.
        clock.advance(Duration.ofSeconds(60));

        store.append(List.of(new IngestEvent("s1", "e2", Instant.now())), "user-1");

        assertEquals(1, rawCollection().countDocuments(), "The second batch must be dropped.");
        assertEquals(1, CapturingLoggerFinder.records().size(), "The store must write one warning.");
        CapturingLoggerFinder.Record record = CapturingLoggerFinder.records().peek();
        assertFalse(record.message().contains("user-1"), "The warning must hold no user id.");
    }

    @Test
    void aFloodInsideOneRefreshIntervalStopsAtTheCap() {
        // Security review of pull request #165, MAJOR 1: the guard adds
        // each accepted batch to the cached estimate, so a flood inside
        // one refresh interval stops at the cap too, not only the count
        // that the last read found. maxEvents 1, five single-event
        // batches inside the same clock instant (no refresh in between):
        // the guard's first read (count 0) allows the first batch, then
        // its own running total of accepted events stops each later one.
        MutableClock clock = new MutableClock(START);
        MongoEventLogStore store = new MongoEventLogStore(database, 30, 1, clock);

        for (int i = 0; i < 5; i++) {
            store.append(List.of(new IngestEvent("s1", "e" + i, Instant.now())), "user-1");
        }

        assertEquals(1, rawCollection().countDocuments(),
                "A flood inside one refresh interval must stop at the cap.");
    }

    @Test
    void aDeleteOfEveryDocumentAsTheTtlDoesLetsTheStoreAcceptABatchAgainAfterTheNextCount() {
        MutableClock clock = new MutableClock(START);
        MongoEventLogStore store = new MongoEventLogStore(database, 30, 1, clock);

        store.append(List.of(new IngestEvent("s1", "e1", Instant.now())), "user-1");
        clock.advance(Duration.ofSeconds(60));
        store.append(List.of(new IngestEvent("s1", "e2", Instant.now())), "user-1");
        assertEquals(1, rawCollection().countDocuments(), "The store must have dropped the second batch.");

        // A TTL index removes an expired document the same way: it
        // leaves the collection with none of the dropped batch's events.
        rawCollection().deleteMany(new Document());
        assertEquals(0, rawCollection().countDocuments());

        clock.advance(Duration.ofSeconds(60));
        store.append(List.of(new IngestEvent("s1", "e3", Instant.now())), "user-1");

        assertEquals(1, rawCollection().countDocuments(), "The store must accept a batch again after the next count.");
    }

    @Test
    void aNullEventThrowsEvenWhenTheStoreIsAlreadyOverTheCap() {
        // MongoDB review of pull request #165, MINOR 7: the null check
        // of each event must run before the cap check, so a batch with
        // a null event always throws, at or above the cap alike.
        MutableClock clock = new MutableClock(START);
        MongoEventLogStore store = new MongoEventLogStore(database, 30, 1, clock);
        store.append(List.of(new IngestEvent("s1", "e1", Instant.now())), "user-1");
        clock.advance(Duration.ofSeconds(60));
        // The store is now over the cap (1 document, cap 1).

        List<IngestEvent> batchWithANullEvent = java.util.Arrays.asList(
                new IngestEvent("s1", "e2", Instant.now()), null);

        assertThrows(NullPointerException.class, () -> store.append(batchWithANullEvent, "user-1"));
    }

    @Test
    void aBatchAtOrBelowTheCapIsNeverDropped() {
        CapturingLoggerFinder.clear();
        MutableClock clock = new MutableClock(START);
        MongoEventLogStore store = new MongoEventLogStore(database, 30, 200_000, clock);

        store.append(List.of(new IngestEvent("s1", "e1", Instant.now())), "user-1");

        assertEquals(1, rawCollection().countDocuments());
        assertTrue(CapturingLoggerFinder.records().isEmpty(), "A batch below the cap must write no warning.");
    }

    private MongoCollection<Document> rawCollection() {
        return database.getCollection("octometer_events");
    }

    /** A {@link Clock} that a test can move forward. */
    private static final class MutableClock extends Clock {
        private volatile Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("This test clock always uses UTC.");
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
