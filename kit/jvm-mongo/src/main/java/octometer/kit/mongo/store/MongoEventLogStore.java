package octometer.kit.mongo.store;

import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.InsertManyOptions;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import octometer.kit.core.ingest.IngestEvent;
import octometer.kit.core.store.EventLogStore;
import org.bson.Document;

/**
 * The MongoDB {@link EventLogStore} of design decision D22 and contract
 * rules C1 to C11 (issue #11). An app gives the {@link MongoDatabase}; the
 * kit never creates a client.
 *
 * <p>Each stored document holds exactly the fields of design section 4.1:
 * {@code _id}, {@code ts}, {@code element}, {@code sessionId}, and {@code
 * userId}. The store builds the document with {@link Document}, so it
 * never adds a {@code _class} field (contract rule C11).
 *
 * <p>The store creates a TTL index on {@code ts} at start, from the
 * environment variable {@code OCTOMETER_RETENTION_DAYS} (default 30 days,
 * contract rule C8). On error code 85 (a conflict of index options), it
 * runs {@code collMod} to change {@code expireAfterSeconds}. Each other
 * index error gives a warn-level log line, and the app start still
 * succeeds.
 *
 * <p>{@link #deleteByUserId} does not yet delete an event; issue #35 adds
 * that behavior.
 */
public final class MongoEventLogStore implements EventLogStore {

    /** The collection name of contract rule C1. */
    public static final String COLLECTION_NAME = "octometer_events";

    private static final String OCTOMETER_RETENTION_DAYS = "OCTOMETER_RETENTION_DAYS";
    private static final String TTL_INDEX_NAME = "ts_ttl";
    private static final int DEFAULT_RETENTION_DAYS = 30;
    private static final int INDEX_OPTIONS_CONFLICT_ERROR_CODE = 85;

    private static final Logger LOGGER = System.getLogger("octometer.kit.mongo");

    private final MongoCollection<Document> collection;

    /**
     * Builds the store on the {@code octometer_events} collection of
     * {@code database}, and creates the TTL index with the retention of
     * {@code OCTOMETER_RETENTION_DAYS}.
     */
    public MongoEventLogStore(MongoDatabase database) {
        this(database, retentionDaysFromEnvironment());
    }

    /**
     * Builds the store with an explicit retention, with no read of the
     * process environment. A test uses this constructor.
     */
    MongoEventLogStore(MongoDatabase database, int retentionDays) {
        Objects.requireNonNull(database, "database must not be null");
        this.collection = database.getCollection(COLLECTION_NAME);
        ensureTtlIndex(database, retentionDays);
    }

    @Override
    public void append(List<IngestEvent> events, String userId) {
        Objects.requireNonNull(events, "events must not be null");
        if (events.isEmpty()) {
            throw new IllegalArgumentException("events must hold one event or more");
        }
        List<Document> documents = new ArrayList<>(events.size());
        for (IngestEvent event : events) {
            Objects.requireNonNull(event, "event must not be null");
            documents.add(toDocument(event, userId));
        }
        collection.insertMany(documents, new InsertManyOptions().ordered(true));
    }

    @Override
    public void deleteByUserId(String userId) {
        throw new UnsupportedOperationException(
                "The store does not implement deleteByUserId yet. Issue #35 adds it.");
    }

    private static Document toDocument(IngestEvent event, String userId) {
        return new Document()
                .append("ts", Date.from(event.ts()))
                .append("element", event.element())
                .append("sessionId", event.sessionId())
                .append("userId", userId);
    }

    private void ensureTtlIndex(MongoDatabase database, int retentionDays) {
        long expireAfterSeconds = (long) retentionDays * 24 * 60 * 60;
        try {
            collection.createIndex(Indexes.ascending("ts"),
                    new IndexOptions().name(TTL_INDEX_NAME).expireAfter(expireAfterSeconds, TimeUnit.SECONDS));
        } catch (MongoCommandException e) {
            if (e.getErrorCode() == INDEX_OPTIONS_CONFLICT_ERROR_CODE) {
                runCollMod(database, expireAfterSeconds);
            } else {
                warnIndexError();
            }
        } catch (RuntimeException e) {
            warnIndexError();
        }
    }

    private void runCollMod(MongoDatabase database, long expireAfterSeconds) {
        try {
            Document command = new Document("collMod", COLLECTION_NAME)
                    .append("index", new Document("keyPattern", new Document("ts", 1))
                            .append("expireAfterSeconds", expireAfterSeconds));
            database.runCommand(command);
        } catch (RuntimeException e) {
            warnIndexError();
        }
    }

    private static void warnIndexError() {
        LOGGER.log(Level.WARNING, "The store could not set up the TTL index on ts. "
                + "The app start continues with no TTL index change.");
    }

    /**
     * Reads {@code OCTOMETER_RETENTION_DAYS} from the process environment,
     * then builds the retention with {@link #retentionDaysFromValue}.
     */
    private static int retentionDaysFromEnvironment() {
        return retentionDaysFromValue(System.getenv(OCTOMETER_RETENTION_DAYS));
    }

    /**
     * Turns the raw text of {@code OCTOMETER_RETENTION_DAYS} into a
     * retention in days (contract rule C8). A {@code null} value gives the
     * default of 30 days, with no warning. A value that is not a positive
     * whole number also gives the default, with one warning; the warning
     * names the variable and never repeats the value.
     */
    static int retentionDaysFromValue(String rawValue) {
        if (rawValue == null) {
            return DEFAULT_RETENTION_DAYS;
        }
        try {
            int days = Integer.parseInt(rawValue.trim());
            if (days > 0) {
                return days;
            }
        } catch (NumberFormatException e) {
            // The text below covers this case too.
        }
        LOGGER.log(Level.WARNING, OCTOMETER_RETENTION_DAYS + " holds a value that is not a positive "
                + "whole number. The store uses the default of 30 days.");
        return DEFAULT_RETENTION_DAYS;
    }
}
