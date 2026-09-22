package octometer.kit.mongo.store;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import octometer.kit.core.ingest.IngestEvent;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
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
    void appendStoresPathAndReferrerHostWithTheSameNamesWhenTheEventRecordHoldsThem() {
        MongoEventLogStore store = new MongoEventLogStore(database);
        IngestEvent event = new IngestEvent("33333333-3333-3333-3333-333333333333", "octo:session-start",
                Instant.parse("2026-09-22T10:00:00Z"), "/checkout", "google.com");

        store.append(event, "user-1");

        Document stored = rawCollection().find().first();
        assertEquals(Set.of("_id", "ts", "element", "sessionId", "userId", "path", "referrerHost"),
                stored.keySet());
        assertEquals("/checkout", stored.getString("path"));
        assertEquals("google.com", stored.getString("referrerHost"));
        assertFalse(stored.containsKey("_class"));
    }

    @Test
    void appendStoresNoKeyForAFieldThatTheEventRecordDoesNotHold() {
        MongoEventLogStore store = new MongoEventLogStore(database);
        IngestEvent event = new IngestEvent("44444444-4444-4444-4444-444444444444", "nav.open", Instant.now());

        store.append(event, "user-1");

        Document stored = rawCollection().find().first();
        assertFalse(stored.containsKey("path"), "The document must hold no path key.");
        assertFalse(stored.containsKey("referrerHost"), "The document must hold no referrerHost key.");
    }

    @Test
    void aClickEventStoresPathAndNoReferrerHostField() {
        MongoEventLogStore store = new MongoEventLogStore(database);
        IngestEvent event = new IngestEvent("55555555-5555-5555-5555-555555555555", "checkout.save",
                Instant.now(), "/checkout", null);

        store.append(event, "user-1");

        Document stored = rawCollection().find().first();
        assertEquals("/checkout", stored.getString("path"));
        assertFalse(stored.containsKey("referrerHost"), "A click event must store no referrerHost field.");
    }

    @Test
    void theStoreWritesThePathValueOfTheEventRecordAsItIsWithNoOwnRule() {
        MongoEventLogStore store = new MongoEventLogStore(database);
        IngestEvent event = new IngestEvent("66666666-6666-6666-6666-666666666666", "checkout.Save",
                Instant.now(), "/Checkout/ABC", null);

        store.append(event, "user-1");

        Document stored = rawCollection().find().first();
        assertEquals("/Checkout/ABC", stored.getString("path"),
                "The store must write the path value of the event record as it is.");
    }

    @Test
    void theCollectionKeepsExactlyTheTwoIndexesAfterAWriteWithTheNewFields() {
        MongoEventLogStore store = new MongoEventLogStore(database, 30);
        IngestEvent event = new IngestEvent("77777777-7777-7777-7777-777777777777", "octo:session-start",
                Instant.now(), "/checkout", "bing.com");

        store.append(event, "user-1");

        Set<String> indexNames = new HashSet<>();
        for (Document index : rawCollection().listIndexes()) {
            indexNames.add(index.getString("name"));
        }
        assertEquals(Set.of("_id_", "ts_ttl"), indexNames,
                "The collection must hold only the _id index and the ts TTL index.");
    }

    @Test
    void appendWritesAMixedBatchWithEachEventKeepingItsOwnFields() {
        MongoEventLogStore store = new MongoEventLogStore(database);
        IngestEvent clickWithPath = new IngestEvent("99999999-9999-9999-9999-999999999999", "checkout.save",
                Instant.now(), "/checkout", null);
        IngestEvent sessionStartWithSource = new IngestEvent("99999999-9999-9999-9999-999999999999",
                "octo:session-start", Instant.now(), null, "bing.com");
        IngestEvent eventWithNeitherField = new IngestEvent("99999999-9999-9999-9999-999999999999", "nav.open",
                Instant.now());

        store.append(List.of(clickWithPath, sessionStartWithSource, eventWithNeitherField), "user-1");

        List<Document> stored = new ArrayList<>();
        for (Document document : rawCollection().find()) {
            stored.add(document);
        }
        assertEquals(3, stored.size());

        Document clickDocument = documentWithElement(stored, "checkout.save");
        assertEquals("/checkout", clickDocument.getString("path"));
        assertFalse(clickDocument.containsKey("referrerHost"), "The click event must store no referrerHost key.");

        Document sessionStartDocument = documentWithElement(stored, "octo:session-start");
        assertFalse(sessionStartDocument.containsKey("path"), "The session-start event must store no path key.");
        assertEquals("bing.com", sessionStartDocument.getString("referrerHost"));

        Document plainDocument = documentWithElement(stored, "nav.open");
        assertFalse(plainDocument.containsKey("path"), "The plain event must store no path key.");
        assertFalse(plainDocument.containsKey("referrerHost"), "The plain event must store no referrerHost key.");
    }

    private static Document documentWithElement(List<Document> documents, String element) {
        for (Document document : documents) {
            if (element.equals(document.getString("element"))) {
                return document;
            }
        }
        throw new AssertionError("No stored document holds the element \"" + element + "\".");
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
    void appendWrapsARealDuplicateKeyFailureWithTheRealCodeAndNoConnectionDetail() {
        MongoEventLogStore store = new MongoEventLogStore(database, 30);
        rawCollection().createIndex(Indexes.ascending("sessionId"), new IndexOptions().unique(true));
        List<IngestEvent> events = List.of(
                new IngestEvent("duplicate-session", "e1", Instant.now()),
                new IngestEvent("duplicate-session", "e2", Instant.now()));

        MongoEventLogStore.EventLogWriteException thrown = assertThrows(
                MongoEventLogStore.EventLogWriteException.class, () -> store.append(events, "user-1"));

        assertEquals(11000, thrown.errorCode(), "A duplicate key must give the real server code.");
        assertNull(thrown.getCause(), "The kit exception must hold no cause.");
        String fullText = (thrown.getMessage() + " " + fullStackTraceText(thrown)).toLowerCase();
        assertFalse(fullText.contains(MONGO.getHost().toLowerCase()), "The text must hold no host.");
        assertFalse(fullText.contains(Integer.toString(MONGO.getFirstMappedPort())), "The text must hold no port.");
        assertFalse(fullText.contains(database.getName().toLowerCase()), "The text must hold no database name.");
        assertFalse(fullText.contains("duplicate-session"), "The text must hold no field value.");
        assertEquals(1, rawCollection().countDocuments());
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

    private static String fullStackTraceText(Throwable throwable) {
        StringWriter stringWriter = new StringWriter();
        throwable.printStackTrace(new PrintWriter(stringWriter));
        return stringWriter.toString();
    }
}
