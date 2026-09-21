package octometer.kit.mongo.store;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoSocketException;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.InsertManyOptions;
import org.bson.conversions.Bson;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import octometer.kit.core.ingest.IngestEvent;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Confirms MAJOR 2 of the security review of pull request #126: a failed
 * write must never let a host, a port, or a database name reach the app
 * (design decision D15). These tests mock the driver, so a real
 * connection failure text (with a real host and a real port) can go into
 * the mocked exception with no live server.
 */
@ExtendWith(MockitoExtension.class)
class MongoEventLogStoreWriteErrorTest {

    private static final String SECRET_HOST = "mongo-primary.internal";
    private static final int SECRET_PORT = 27017;
    private static final String SECRET_DATABASE = "octometer_app_42";

    @Test
    void aCommandFailureThrowsTheKitExceptionWithNoConnectionDetailAndNoCause() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(database.getCollection("octometer_events")).thenReturn(collection);
        when(collection.createIndex(any(Bson.class), any(IndexOptions.class))).thenReturn("ts_ttl");
        MongoCommandException driverException = commandExceptionWithConnectionDetail();
        when(collection.insertMany(any(List.class), any(InsertManyOptions.class))).thenThrow(driverException);

        MongoEventLogStore store = new MongoEventLogStore(database, 30);

        MongoEventLogStore.EventLogWriteException thrown = assertThrows(
                MongoEventLogStore.EventLogWriteException.class,
                () -> store.append(List.of(new IngestEvent("s1", "e1", Instant.now())), "user-1"));

        assertEquals(13, thrown.errorCode());
        assertNull(thrown.getCause(), "The kit exception must hold no cause.");
        assertNoConnectionDetail(thrown.getMessage());
        assertNoConnectionDetail(fullStackTraceText(thrown));
    }

    @Test
    void aNonCommandFailureAlsoThrowsTheKitExceptionWithNoCause() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(database.getCollection("octometer_events")).thenReturn(collection);
        when(collection.createIndex(any(Bson.class), any(IndexOptions.class))).thenReturn("ts_ttl");
        MongoSocketException driverException = new MongoSocketException(
                "Exception opening socket to " + SECRET_HOST + ":" + SECRET_PORT,
                new ServerAddress(SECRET_HOST, SECRET_PORT));
        when(collection.insertMany(any(List.class), any(InsertManyOptions.class))).thenThrow(driverException);

        MongoEventLogStore store = new MongoEventLogStore(database, 30);

        MongoEventLogStore.EventLogWriteException thrown = assertThrows(
                MongoEventLogStore.EventLogWriteException.class,
                () -> store.append(List.of(new IngestEvent("s1", "e1", Instant.now())), "user-1"));

        assertEquals(-1, thrown.errorCode());
        assertNull(thrown.getCause(), "The kit exception must hold no cause.");
        assertNoConnectionDetail(thrown.getMessage());
        assertNoConnectionDetail(fullStackTraceText(thrown));
    }

    private static MongoCommandException commandExceptionWithConnectionDetail() {
        BsonDocument response = new BsonDocument();
        response.put("ok", new BsonInt32(0));
        response.put("code", new BsonInt32(13));
        response.put("codeName", new BsonString("Unauthorized"));
        response.put("errmsg", new BsonString("not authorized on " + SECRET_DATABASE
                + " to execute command { insert: \"octometer_events\", ordered: true, $db: \""
                + SECRET_DATABASE + "\" }"));
        return new MongoCommandException(response, new ServerAddress(SECRET_HOST, SECRET_PORT));
    }

    private static void assertNoConnectionDetail(String text) {
        String lowerCaseText = text.toLowerCase();
        assertFalse(lowerCaseText.contains(SECRET_HOST.toLowerCase()), "The text must hold no host: " + text);
        assertFalse(lowerCaseText.contains(Integer.toString(SECRET_PORT)), "The text must hold no port: " + text);
        assertFalse(lowerCaseText.contains(SECRET_DATABASE.toLowerCase()), "The text must hold no database name: " + text);
    }

    private static String fullStackTraceText(Throwable throwable) {
        StringWriter stringWriter = new StringWriter();
        throwable.printStackTrace(new PrintWriter(stringWriter));
        return stringWriter.toString();
    }
}
