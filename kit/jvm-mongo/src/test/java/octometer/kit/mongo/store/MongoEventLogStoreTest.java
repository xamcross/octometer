package octometer.kit.mongo.store;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import octometer.kit.core.ingest.IngestEvent;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests of {@link MongoEventLogStore} against a real MongoDB
 * server (contract rules C1 to C11, design decision D22, issue #11). Each
 * test needs Docker; a machine with no Docker skips the whole class. The
 * container uses a random host port, and the extension removes it after
 * the class.
 */
@Testcontainers(disabledWithoutDocker = true)
class MongoEventLogStoreTest {

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
    void appendStoresExactlyTheFieldsOfSection41WithNoClassField() {
        MongoEventLogStore store = new MongoEventLogStore(database);
        IngestEvent event = new IngestEvent("11111111-1111-1111-1111-111111111111", "checkout.save",
                Instant.parse("2026-09-21T10:00:00Z"));

        store.append(event, "user-1");

        Document stored = rawCollection().find().first();
        assertEquals(Set.of("_id", "ts", "element", "sessionId", "userId"), stored.keySet());
        assertEquals("checkout.save", stored.getString("element"));
        assertEquals("11111111-1111-1111-1111-111111111111", stored.getString("sessionId"));
        assertEquals("user-1", stored.getString("userId"));
        assertFalse(stored.containsKey("_class"));
    }

    @Test
    void appendAcceptsANullUserIdForAnAnonymousClick() {
        MongoEventLogStore store = new MongoEventLogStore(database);
        IngestEvent event = new IngestEvent("22222222-2222-2222-2222-222222222222", "nav.open", Instant.now());

        store.append(event, null);

        Document stored = rawCollection().find().first();
        assertTrue(stored.containsKey("userId"));
        assertNull(stored.get("userId"));
    }

    @Test
    void appendInsertsAWholeBatchWithOneCall() {
        MongoEventLogStore store = new MongoEventLogStore(database);
        List<IngestEvent> events = List.of(
                new IngestEvent("s1", "e1", Instant.now()),
                new IngestEvent("s1", "e2", Instant.now()),
                new IngestEvent("s1", "e3", Instant.now()));

        store.append(events, "user-1");

        assertEquals(3, rawCollection().countDocuments());
    }

    @Test
    void appendRejectsAnEmptyBatch() {
        MongoEventLogStore store = new MongoEventLogStore(database);

        assertThrows(IllegalArgumentException.class, () -> store.append(List.of(), "user-1"));
        assertEquals(0, rawCollection().countDocuments());
    }

    @Test
    void appendRejectsANullBatch() {
        MongoEventLogStore store = new MongoEventLogStore(database);

        assertThrows(NullPointerException.class, () -> store.append((List<IngestEvent>) null, "user-1"));
    }

    @Test
    void aSecondStartWithADifferentRetentionChangesExpireAfterSecondsAndDoesNotFail() {
        new MongoEventLogStore(database, 30);
        assertEquals(30L * 24 * 60 * 60, ttlExpireAfterSeconds());

        new MongoEventLogStore(database, 10);

        assertEquals(10L * 24 * 60 * 60, ttlExpireAfterSeconds());
    }

    @Test
    void theCollectionKeepsExactlyTheDefaultIdIndexAndTheTtlIndexOnTs() {
        new MongoEventLogStore(database, 30);

        List<Document> indexes = new ArrayList<>();
        for (Document index : rawCollection().listIndexes()) {
            indexes.add(index);
        }
        assertEquals(2, indexes.size());

        Document ttlIndex = null;
        for (Document index : indexes) {
            if ("ts_ttl".equals(index.getString("name"))) {
                ttlIndex = index;
            } else {
                assertEquals("_id_", index.getString("name"));
            }
        }
        assertNotNull(ttlIndex, "The index \"ts_ttl\" is missing.");
        assertEquals(new Document("ts", 1), ttlIndex.get("key"));
        assertEquals(30L * 24 * 60 * 60, ((Number) ttlIndex.get("expireAfterSeconds")).longValue());
    }

    private MongoCollection<Document> rawCollection() {
        return database.getCollection("octometer_events");
    }

    private long ttlExpireAfterSeconds() {
        for (Document index : rawCollection().listIndexes()) {
            if (index.containsKey("expireAfterSeconds")) {
                return ((Number) index.get("expireAfterSeconds")).longValue();
            }
        }
        throw new AssertionError("No TTL index found on octometer_events.");
    }
}
