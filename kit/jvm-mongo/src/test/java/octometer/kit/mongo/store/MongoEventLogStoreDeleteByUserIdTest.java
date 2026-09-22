package octometer.kit.mongo.store;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import octometer.kit.core.ingest.IngestEvent;
import octometer.kit.core.store.DeletionResult;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests of {@link MongoEventLogStore#deleteByUserId} against a
 * real MongoDB server (contract rule C43, issue #35). Each test needs
 * Docker; a machine with no Docker skips the whole class.
 */
@Testcontainers(disabledWithoutDocker = true)
class MongoEventLogStoreDeleteByUserIdTest {

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

    /**
     * Covers each acceptance criterion of issue #35: the user's own
     * events are gone, the anonymous event of the same session is gone
     * (contract rule C43), a second user of that session stays, and an
     * anonymous event of another session stays.
     */
    @Test
    void deleteByUserIdRemovesTheUsersEventsAndTheAnonymousEventsOfTheSameSession() {
        MongoEventLogStore store = new MongoEventLogStore(database, 30);
        store.append(event("session-a", "checkout.save"), "user-1");
        store.append(event("session-a", "nav.open"), "user-2");
        store.append(event("session-a", "nav.menu.open"), null);
        store.append(event("session-b", "checkout.save"), "user-1");
        store.append(event("session-c", "nav.open"), null);

        DeletionResult result = store.deleteByUserId("user-1");

        assertEquals(2, result.userEventCount(), "The two events of user-1 must be counted.");
        assertEquals(1, result.anonymousEventCount(), "The one anonymous event of session-a must be counted.");
        assertEquals(3, result.totalCount());
        assertTrue(result.complete(), "The call found no new session id on its last pass.");

        List<Document> remaining = rawCollection().find().into(new java.util.ArrayList<>());
        assertEquals(2, remaining.size(), "Only the second user's event and the other session's anonymous event stay.");
        assertTrue(remaining.stream().anyMatch(
                doc -> "session-a".equals(doc.getString("sessionId")) && "user-2".equals(doc.getString("userId"))),
                "The event of the second user in session-a must stay.");
        assertTrue(remaining.stream().anyMatch(
                doc -> "session-c".equals(doc.getString("sessionId")) && doc.get("userId") == null),
                "The anonymous event of session-c must stay.");
        assertEquals(0, rawCollection().countDocuments(Filters.eq("userId", "user-1")),
                "No document of user-1 must remain.");
    }

    @Test
    void deleteByUserIdWithNoMatchingEventReturnsTwoZeroCounts() {
        MongoEventLogStore store = new MongoEventLogStore(database, 30);
        store.append(event("session-a", "checkout.save"), "user-2");

        DeletionResult result = store.deleteByUserId("user-1");

        assertEquals(0, result.userEventCount());
        assertEquals(0, result.anonymousEventCount());
        assertTrue(result.complete());
        assertEquals(1, rawCollection().countDocuments());
    }

    // deleteByUserIdRejectsANullValue and deleteByUserIdRejectsAnEmptyText
    // moved to MongoEventLogStoreDeleteErrorTest, which needs no Docker
    // (MINOR 3 of the MongoDB review of pull request #157).
    //
    // MongoEventLogStoreDeleteErrorTest also holds the pass-loop tests
    // of the BLOCKER of pull request #157 (a new session between step
    // (a) and step (c), and a throw before step (c)). A Mockito mock
    // gives exact control over the session id list of each pass; a real
    // server gives no such control with no added race code.

    /**
     * Confirms the cost of contract rule C8: the collection holds only
     * the default {@code _id} index and the TTL index on {@code ts}
     * (issue #35 step 5, no new index). The query plan of each filter
     * shape that {@code deleteByUserId} runs is a full collection scan,
     * never an index seek. The PR text of issue #35 records this plan.
     *
     * <p>This test explains a {@code find} with the two filter shapes of
     * {@code deleteByUserId}, not the {@code deleteMany} commands
     * themselves. The MongoDB planner picks a plan from the filter
     * alone, and a {@code find} and a {@code deleteMany} with the same
     * filter share the same plan, so the find plan is a correct proxy
     * for the delete plan.
     */
    @Test
    void theQueryPlanOfEachDeleteFilterIsACollectionScanWithNoIndex() {
        MongoEventLogStore store = new MongoEventLogStore(database, 30);
        store.append(event("session-a", "checkout.save"), "user-1");
        store.append(event("session-a", "nav.menu.open"), null);

        Document userIdPlan = rawCollection()
                .find(Filters.and(Filters.eq("userId", "user-1"), Filters.in("sessionId", List.of("session-a"))))
                .explain();
        Document anonymousPlan = rawCollection()
                .find(Filters.and(Filters.eq("userId", null), Filters.in("sessionId", List.of("session-a"))))
                .explain();

        assertEquals("COLLSCAN", winningPlanStage(userIdPlan),
                "A filter on userId and sessionId must scan the collection; contract rule C8 indexes only _id and ts.");
        assertEquals("COLLSCAN", winningPlanStage(anonymousPlan),
                "A filter on userId and sessionId must scan the collection; contract rule C8 indexes only _id and ts.");
    }

    private static String winningPlanStage(Document explainResult) {
        Document queryPlanner = explainResult.get("queryPlanner", Document.class);
        Document winningPlan = queryPlanner.get("winningPlan", Document.class);
        // MongoDB 7.0 can nest the scan stage under a SHARDING_FILTER or a
        // PROJECTION stage. The scan stage of this collection, with no
        // predicate index, is always the innermost stage.
        Document stage = winningPlan;
        while (stage.get("inputStage", Document.class) != null) {
            stage = stage.get("inputStage", Document.class);
        }
        return stage.getString("stage");
    }

    private static IngestEvent event(String sessionId, String element) {
        return new IngestEvent(sessionId, element, Instant.now());
    }

    private MongoCollection<Document> rawCollection() {
        return database.getCollection("octometer_events");
    }
}
