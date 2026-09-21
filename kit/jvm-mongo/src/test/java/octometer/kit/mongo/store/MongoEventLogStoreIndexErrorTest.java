package octometer.kit.mongo.store;

import com.mongodb.MongoCommandException;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests of the TTL index setup of {@link MongoEventLogStore} (design
 * decision D22, issue #11 step 4 and step 5). These tests mock the driver,
 * so they run with no MongoDB server and with no Docker image.
 */
@ExtendWith(MockitoExtension.class)
class MongoEventLogStoreIndexErrorTest {

    @Test
    void errorCodeEightyFiveRunsCollModWithNoWarning() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(database.getCollection("octometer_events")).thenReturn(collection);
        when(collection.createIndex(any(Bson.class), any(IndexOptions.class)))
                .thenThrow(commandException(85, "IndexOptionsConflict"));
        when(database.runCommand(any(Bson.class))).thenReturn(new Document("ok", 1.0));
        CapturingLoggerFinder.clear();

        new MongoEventLogStore(database, 10);

        ArgumentCaptor<Bson> commandCaptor = ArgumentCaptor.forClass(Bson.class);
        verify(database).runCommand(commandCaptor.capture());
        assertTrue(commandCaptor.getValue() instanceof Document);
        Document command = (Document) commandCaptor.getValue();
        assertEquals("octometer_events", command.getString("collMod"));
        assertTrue(CapturingLoggerFinder.records().isEmpty());
    }

    @Test
    void aDifferentErrorCodeGivesAWarnLevelLogLineAndTheStoreStillWorks() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(database.getCollection("octometer_events")).thenReturn(collection);
        when(collection.createIndex(any(Bson.class), any(IndexOptions.class)))
                .thenThrow(commandException(13, "Unauthorized"));
        CapturingLoggerFinder.clear();

        MongoEventLogStore store = new MongoEventLogStore(database, 30);

        assertEquals(1, CapturingLoggerFinder.records().size());
        CapturingLoggerFinder.Record record = CapturingLoggerFinder.records().peek();
        assertEquals(java.lang.System.Logger.Level.WARNING, record.level());
        assertTrue(record.message().toLowerCase().contains("index"));
        verify(database, never()).runCommand(any(Bson.class));

        // The store still works: an append call reaches the collection.
        store.append(java.util.List.of(
                new octometer.kit.core.ingest.IngestEvent("s1", "e1", java.time.Instant.now())), "user-1");
        verify(collection).insertMany(any(List.class), any(com.mongodb.client.model.InsertManyOptions.class));
    }

    @Test
    void collModFailureAlsoGivesAWarnLevelLogLine() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(database.getCollection("octometer_events")).thenReturn(collection);
        when(collection.createIndex(any(Bson.class), any(IndexOptions.class)))
                .thenThrow(commandException(85, "IndexOptionsConflict"));
        when(database.runCommand(any(Bson.class))).thenThrow(commandException(2, "BadValue"));
        CapturingLoggerFinder.clear();

        new MongoEventLogStore(database, 10);

        assertEquals(1, CapturingLoggerFinder.records().size());
        assertEquals(java.lang.System.Logger.Level.WARNING,
                CapturingLoggerFinder.records().peek().level());
    }

    @Test
    void deleteByUserIdThrowsAnUnsupportedOperationException() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(database.getCollection("octometer_events")).thenReturn(collection);

        MongoEventLogStore store = new MongoEventLogStore(database, 30);

        UnsupportedOperationException exception = assertThrows(UnsupportedOperationException.class,
                () -> store.deleteByUserId("user-1"));
        assertTrue(exception.getMessage().contains("#35"));
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
