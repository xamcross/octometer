package octometer.kit.mongo.store;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoWriteConcernException;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.bulk.WriteConcernError;
import com.mongodb.client.DistinctIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.result.DeleteResult;
import octometer.kit.core.store.DeletionResult;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests of {@link MongoEventLogStore#deleteByUserId} that need no
 * MongoDB server (MINOR 3 of the MongoDB review of pull request #157).
 * Each test mocks the driver, so it runs with no Docker image.
 *
 * <p>These tests also hold the regression tests of the BLOCKER of pull
 * request #157: the pass loop of {@code deleteByUserId}, the error
 * code of each catch branch (MongoDB review MINOR 1 and MINOR 2), the
 * write-concern guard (MongoDB review MAJOR 2), the session id batch
 * (MongoDB review MINOR 3, privacy review MINOR 3), and the sentinel
 * test of the privacy review MAJOR 1.
 */
@ExtendWith(MockitoExtension.class)
class MongoEventLogStoreDeleteErrorTest {

    @Test
    void deleteByUserIdRejectsANullValue() {
        MongoEventLogStore store = storeWithMockedCollection(mock(MongoCollection.class));

        assertThrows(NullPointerException.class, () -> store.deleteByUserId(null));
    }

    @Test
    void deleteByUserIdRejectsAnEmptyText() {
        MongoEventLogStore store = storeWithMockedCollection(mock(MongoCollection.class));

        assertThrows(IllegalArgumentException.class, () -> store.deleteByUserId(""));
    }

    @Test
    void aMongoWriteExceptionGivesTheWriteErrorCode() {
        MongoCollection<Document> collection = mock(MongoCollection.class);
        MongoEventLogStore store = storeWithMockedCollection(collection);
        stubSessionIds(collection, List.of("session-a"));
        when(collection.deleteMany(any(Bson.class)))
                .thenThrow(new MongoWriteException(new WriteError(11000, "a test write error", new BsonDocument()),
                        new ServerAddress()));

        MongoEventLogStore.EventLogDeleteException thrown = assertThrows(
                MongoEventLogStore.EventLogDeleteException.class, () -> store.deleteByUserId("user-1"));

        assertEquals(11000, thrown.errorCode());
    }

    @Test
    void aMongoWriteConcernExceptionGivesTheWriteConcernErrorCode() {
        MongoCollection<Document> collection = mock(MongoCollection.class);
        MongoEventLogStore store = storeWithMockedCollection(collection);
        stubSessionIds(collection, List.of("session-a"));
        when(collection.deleteMany(any(Bson.class)))
                .thenThrow(new MongoWriteConcernException(
                        new WriteConcernError(64, "WriteConcernFailed", "a test write concern error", new BsonDocument()),
                        new ServerAddress()));

        MongoEventLogStore.EventLogDeleteException thrown = assertThrows(
                MongoEventLogStore.EventLogDeleteException.class, () -> store.deleteByUserId("user-1"));

        assertEquals(64, thrown.errorCode());
    }

    @Test
    void aMongoCommandExceptionGivesItsErrorCode() {
        MongoCollection<Document> collection = mock(MongoCollection.class);
        MongoEventLogStore store = storeWithMockedCollection(collection);
        stubSessionIds(collection, List.of("session-a"));
        when(collection.deleteMany(any(Bson.class))).thenThrow(commandException(13, "Unauthorized"));

        MongoEventLogStore.EventLogDeleteException thrown = assertThrows(
                MongoEventLogStore.EventLogDeleteException.class, () -> store.deleteByUserId("user-1"));

        assertEquals(13, thrown.errorCode());
    }

    @Test
    void anyOtherRuntimeExceptionGivesErrorCodeMinusOne() {
        MongoCollection<Document> collection = mock(MongoCollection.class);
        MongoEventLogStore store = storeWithMockedCollection(collection);
        stubSessionIds(collection, List.of("session-a"));
        when(collection.deleteMany(any(Bson.class))).thenThrow(new IllegalStateException("a test failure"));

        MongoEventLogStore.EventLogDeleteException thrown = assertThrows(
                MongoEventLogStore.EventLogDeleteException.class, () -> store.deleteByUserId("user-1"));

        assertEquals(-1, thrown.errorCode());
    }

    @Test
    void theExceptionMessageAndTheCauseHoldNoConnectionDetail() {
        MongoCollection<Document> collection = mock(MongoCollection.class);
        MongoEventLogStore store = storeWithMockedCollection(collection);
        stubSessionIds(collection, List.of("session-a"));
        when(collection.deleteMany(any(Bson.class)))
                .thenThrow(commandException(13, "not authorized on octometer_app_42 to execute command"));

        MongoEventLogStore.EventLogDeleteException thrown = assertThrows(
                MongoEventLogStore.EventLogDeleteException.class, () -> store.deleteByUserId("user-1"));

        assertNull(thrown.getCause(), "The kit exception must hold no cause.");
        assertFalse(thrown.getMessage().toLowerCase().contains("octometer_app_42"),
                "The message must hold no database name.");
    }

    /**
     * Confirms MAJOR 2 of the MongoDB review: an unacknowledged write
     * concern gives a clear {@link MongoEventLogStore.EventLogDeleteException}
     * instead of the {@link UnsupportedOperationException} of the
     * driver, and the second command still runs when the first
     * succeeded.
     */
    @Test
    void anUnacknowledgedAnonymousDeleteThrowsBeforeTheUserDelete() {
        MongoCollection<Document> collection = mock(MongoCollection.class);
        MongoEventLogStore store = storeWithMockedCollection(collection);
        stubSessionIds(collection, List.of("session-a"));
        when(collection.deleteMany(any(Bson.class))).thenReturn(DeleteResult.unacknowledged());

        MongoEventLogStore.EventLogDeleteException thrown = assertThrows(
                MongoEventLogStore.EventLogDeleteException.class, () -> store.deleteByUserId("user-1"));

        assertEquals(-2, thrown.errorCode());
        verify(collection, times(1)).deleteMany(any(Bson.class));
    }

    @Test
    void anUnacknowledgedUserDeleteRunsAfterAnAcknowledgedAnonymousDelete() {
        MongoCollection<Document> collection = mock(MongoCollection.class);
        MongoEventLogStore store = storeWithMockedCollection(collection);
        stubSessionIds(collection, List.of("session-a"));
        when(collection.deleteMany(any(Bson.class)))
                .thenReturn(DeleteResult.acknowledged(1))
                .thenReturn(DeleteResult.unacknowledged());

        MongoEventLogStore.EventLogDeleteException thrown = assertThrows(
                MongoEventLogStore.EventLogDeleteException.class, () -> store.deleteByUserId("user-1"));

        assertEquals(-2, thrown.errorCode());
        // The second command (the user delete) ran; the mock answered
        // it with an unacknowledged result.
        verify(collection, times(2)).deleteMany(any(Bson.class));
    }

    /**
     * Confirms MAJOR 1 of the privacy review: no captured log record,
     * and no exception message, holds the sentinel user id or the
     * sentinel session id. {@link CapturingLoggerFinder} intercepts
     * each {@link System.Logger} call of this module, so this test
     * reads it, not a {@code java.util.logging.Handler} (this class
     * gives no {@code System.Logger} record to {@code java.util.logging}
     * at all, because a test-only service file replaces the finder).
     */
    @Test
    void deleteByUserIdLogsNoUserIdAndNoSessionIdAndTheExceptionMessageHoldsNeither() {
        String sentinelUserId = "sentinel-user-4a1c9d";
        String sentinelSessionId = "sentinel-session-2e77af";
        MongoCollection<Document> collection = mock(MongoCollection.class);
        MongoEventLogStore store = storeWithMockedCollection(collection);
        stubSessionIds(collection, List.of(sentinelSessionId));
        when(collection.deleteMany(any(Bson.class))).thenThrow(commandException(13, "Unauthorized"));
        CapturingLoggerFinder.clear();

        MongoEventLogStore.EventLogDeleteException thrown = assertThrows(
                MongoEventLogStore.EventLogDeleteException.class, () -> store.deleteByUserId(sentinelUserId));

        assertNull(thrown.getCause());
        assertFalse(thrown.getMessage().contains(sentinelUserId), "The message must not hold the user id.");
        assertFalse(thrown.getMessage().contains(sentinelSessionId), "The message must not hold the session id.");
        for (CapturingLoggerFinder.Record record : CapturingLoggerFinder.records()) {
            assertFalse(record.message().contains(sentinelUserId), "A log line must not hold the user id.");
            assertFalse(record.message().contains(sentinelSessionId), "A log line must not hold the session id.");
        }
    }

    /**
     * Proves the fix of the BLOCKER of pull request #157: a session
     * that the first session read of step (a) never finds, because it
     * starts after that read, is still found and cleaned by the second
     * read of step (d).
     */
    @Test
    void aSessionThatAppearsOnlyOnTheSecondReadIsRemovedOnTheSecondPass() {
        MongoCollection<Document> collection = mock(MongoCollection.class);
        MongoEventLogStore store = storeWithMockedCollection(collection);
        stubSessionIds(collection, List.of("session-a"), List.of("session-new"), List.of());
        when(collection.deleteMany(any(Bson.class))).thenReturn(DeleteResult.acknowledged(1));

        DeletionResult result = store.deleteByUserId("user-1");

        assertEquals(2, result.userEventCount(), "One user delete of session-a, one of session-new.");
        assertEquals(2, result.anonymousEventCount(), "One anonymous delete of session-a, one of session-new.");
        assertTrue(result.complete());
        verify(collection, times(4)).deleteMany(any(Bson.class));
    }

    /**
     * Proves the other side of the same fix. A throw before the user
     * delete of a pass never removes the anonymous events of that
     * pass. A second, separate call then finishes the user delete
     * that the throw stopped.
     */
    @Test
    void aThrowInTheUserDeleteLeavesTheAnonymousDeleteInPlaceForASecondCall() {
        MongoCollection<Document> collection = mock(MongoCollection.class);
        MongoEventLogStore store = storeWithMockedCollection(collection);
        // Read 1: the first call's step (a). The throw in step (c)
        // stops that call before it reads again. Read 2: the second
        // call's step (a), the same session id (its user event still
        // stands). Read 3: the second call's step (d), now empty.
        stubSessionIds(collection, List.of("session-a"), List.of("session-a"), List.of());
        when(collection.deleteMany(any(Bson.class)))
                .thenReturn(DeleteResult.acknowledged(1))
                .thenThrow(new IllegalStateException("a test failure before the user delete"));

        assertThrows(MongoEventLogStore.EventLogDeleteException.class, () -> store.deleteByUserId("user-1"));
        verify(collection, times(2)).deleteMany(any(Bson.class));

        // A second, separate call: the anonymous events are already
        // gone (0 more to delete), and the user delete that the throw
        // stopped now succeeds.
        when(collection.deleteMany(any(Bson.class)))
                .thenReturn(DeleteResult.acknowledged(0))
                .thenReturn(DeleteResult.acknowledged(1));

        DeletionResult secondResult = store.deleteByUserId("user-1");

        assertEquals(1, secondResult.userEventCount());
        assertEquals(0, secondResult.anonymousEventCount());
        assertTrue(secondResult.complete());
    }

    /**
     * Proves the pass bound: a session that the read of step (d) keeps
     * finding stops the call after 3 passes, and the answer holds
     * {@code complete: false}.
     */
    @Test
    void thePassBoundStopsAtThreePassesAndTheCompleteFlagIsFalse() {
        MongoCollection<Document> collection = mock(MongoCollection.class);
        MongoEventLogStore store = storeWithMockedCollection(collection);
        // The read of step (a), then the read of step (d) after each of
        // the 3 passes: 4 reads in total, each one non-empty.
        stubSessionIds(collection, List.of("session-0"), List.of("session-1"), List.of("session-2"),
                List.of("session-3"));
        when(collection.deleteMany(any(Bson.class))).thenReturn(DeleteResult.acknowledged(1));

        DeletionResult result = store.deleteByUserId("user-1");

        assertFalse(result.complete(), "The bound of 3 passes stopped the call.");
        assertEquals(3, result.userEventCount());
        assertEquals(3, result.anonymousEventCount());
        verify(collection, times(6)).deleteMany(any(Bson.class));
    }

    /**
     * Confirms MINOR 3 of the privacy review: a session id list of more
     * than 1 000 entries goes to {@code deleteMany} in batches of
     * 1 000, so no single command holds more than that many ids.
     */
    @Test
    void aSessionIdListOfMoreThanOneThousandGoesInBatches() {
        MongoCollection<Document> collection = mock(MongoCollection.class);
        MongoEventLogStore store = storeWithMockedCollection(collection);
        List<String> sessionIds = new ArrayList<>();
        for (int i = 0; i < 1_500; i++) {
            sessionIds.add("session-" + i);
        }
        stubSessionIds(collection, sessionIds, List.of());
        when(collection.deleteMany(any(Bson.class))).thenReturn(DeleteResult.acknowledged(1));

        DeletionResult result = store.deleteByUserId("user-1");

        // 1 500 session ids need 2 batches of at most 1 000 each, for
        // the anonymous delete and for the user delete: 4 calls.
        ArgumentCaptor<Bson> filterCaptor = ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(4)).deleteMany(filterCaptor.capture());
        for (Bson filter : filterCaptor.getAllValues()) {
            BsonDocument document = filter.toBsonDocument(Document.class,
                    com.mongodb.MongoClientSettings.getDefaultCodecRegistry());
            int batchSize = sessionIdInArray(document).size();
            assertTrue(batchSize <= 1000, "One command must not hold more than 1 000 session ids.");
        }
        assertEquals(4, result.userEventCount() + result.anonymousEventCount(),
                "One deleted event per batch call, 4 calls in total.");
    }

    /**
     * Finds the {@code $in} array of the {@code sessionId} condition of
     * {@code filter}, whether the driver keeps the filter as one flat
     * document, or wraps it in a {@code $and} array.
     */
    private static org.bson.BsonArray sessionIdInArray(BsonDocument filter) {
        if (filter.containsKey("sessionId")) {
            return filter.getDocument("sessionId").getArray("$in");
        }
        for (BsonDocument clause : filter.getArray("$and").stream()
                .map(value -> value.asDocument())
                .toList()) {
            if (clause.containsKey("sessionId")) {
                return clause.getDocument("sessionId").getArray("$in");
            }
        }
        throw new AssertionError("The filter holds no sessionId condition: " + filter.toJson());
    }

    @SafeVarargs
    private static void stubSessionIds(MongoCollection<Document> collection, List<String> firstRead,
            List<String>... laterReads) {
        DistinctIterable<String> firstIterable = distinctIterableOf(firstRead);
        DistinctIterable<String>[] laterIterables = new DistinctIterable[laterReads.length];
        for (int i = 0; i < laterReads.length; i++) {
            laterIterables[i] = distinctIterableOf(laterReads[i]);
        }
        when(collection.distinct(eq("sessionId"), any(Bson.class), eq(String.class)))
                .thenReturn(firstIterable, laterIterables);
    }

    @SuppressWarnings("unchecked")
    private static DistinctIterable<String> distinctIterableOf(List<String> sessionIds) {
        DistinctIterable<String> iterable = mock(DistinctIterable.class);
        when(iterable.into(any(List.class))).thenAnswer(invocation -> {
            List<String> target = invocation.getArgument(0);
            target.addAll(sessionIds);
            return target;
        });
        return iterable;
    }

    @SuppressWarnings("unchecked")
    private static MongoEventLogStore storeWithMockedCollection(MongoCollection<Document> collection) {
        MongoDatabase database = mock(MongoDatabase.class);
        when(database.getCollection("octometer_events")).thenReturn(collection);
        when(collection.createIndex(any(Bson.class), any(IndexOptions.class))).thenReturn("ts_ttl");
        return new MongoEventLogStore(database, 30);
    }

    private static MongoCommandException commandException(int code, String codeName) {
        BsonDocument response = new BsonDocument();
        response.put("ok", new BsonInt32(0));
        response.put("code", new BsonInt32(code));
        response.put("codeName", new BsonString(codeName));
        response.put("errmsg", new BsonString("A test error with code " + code));
        return new MongoCommandException(response, new ServerAddress());
    }
}
