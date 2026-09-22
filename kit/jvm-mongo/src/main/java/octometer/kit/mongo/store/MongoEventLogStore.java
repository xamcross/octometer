package octometer.kit.mongo.store;

import com.mongodb.MongoBulkWriteException;
import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.InsertManyOptions;
import com.mongodb.client.result.DeleteResult;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import octometer.kit.core.ingest.IngestEvent;
import octometer.kit.core.store.DeletionResult;
import octometer.kit.core.store.EventLogStore;
import org.bson.Document;
import org.bson.conversions.Bson;

/**
 * The MongoDB {@link EventLogStore} of design decision D22 and contract
 * rules C1, C2, C3, C6, C7, C8, and C11 (issue #11). Rule C4 and rule C5
 * are the concern of {@code kit/jvm-core}. Rule C9 and rule C10 are the
 * concern of the monitor. An app gives the {@link MongoDatabase}; the kit
 * never creates a client.
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
 * index error gives a warn-level log line with the failed step and the
 * numeric error code, never the server text, and the app start still
 * succeeds.
 *
 * <p>{@link #append} sends one ordered {@code insertMany} call for the
 * whole batch. It opens no transaction: the constructor takes only a
 * {@link MongoDatabase}, and a transaction needs a client session. A
 * transaction would also cost two more round trips on an Atlas M0
 * cluster. A failure in the middle of the batch therefore keeps each
 * event before the failure, and it drops each event after the failure.
 * The caller gets an {@link EventLogWriteException}, with no host, no
 * port, and no database name in its message (design decision D15). The
 * adapter maps it to status 500, the tracker retries the batch one time
 * (design decision D24), and the retry writes new {@code _id} values. The
 * monitor keys on {@code _id} (contract rule C24), thus it cannot drop
 * the repeated events. This is the accepted cost of at-least-once
 * delivery on an M0 cluster.
 *
 * <p>The store sets no write concern, no read preference, and no
 * timeout. It uses the settings of the {@link MongoDatabase} of the app.
 * The driver has no socket read timeout by default, so a blocked primary
 * holds the calling thread for ever. An app that calls {@link #append}
 * from a thread pool, for example {@code Dispatchers.IO} of the Ktor
 * route (issue #12), must set a timeout on its own {@code
 * MongoClientSettings}: {@code timeoutMS} with driver 5.2 or newer, or a
 * socket read timeout plus a write concern with a {@code wtimeout} with
 * driver 5.0.
 *
 * <p>{@link #deleteByUserId} implements contract rule C43 (issue #35). A
 * call reads the distinct {@code sessionId} values of the given user id
 * first, then it runs two {@code deleteMany} calls: one for each event
 * of that user id, and one for each anonymous event of those sessions.
 * The read and the two deletes are three separate MongoDB commands, not
 * one transaction; the class comment above states why this store opens
 * no transaction. The Javadoc of {@link EventLogStore#deleteByUserId}
 * states the risk of a write between the steps.
 *
 * <p>Contract rule C8 gives the collection only two indexes: one on
 * {@code _id}, and the TTL index on {@code ts}. Issue #35 adds no new
 * index, because the erasure is a rare, owner-triggered action. Each of
 * the three MongoDB commands of {@code deleteByUserId} is therefore a
 * full collection scan, never an index seek. Design decision D21 caps
 * the collection at 200 000 documents, so the scan cost stays bounded.
 * {@code MongoEventLogStoreDeleteByUserIdTest} runs {@code explain} on
 * the two filter shapes and asserts the {@code COLLSCAN} stage.
 *
 * <p><strong>The erasure order for an app team (design decision
 * D15).</strong> Call this method first, in the app. Wait for one full
 * poll cycle of the monitor (the refresh time of its mode). Then call
 * the erasure route of the monitor, {@code DELETE
 * /api/apps/{appId}/events?userId=<id>} (issue #61). A call to the
 * monitor route before that wait lets the poll cycle read the erased
 * events again from this store.
 */
public final class MongoEventLogStore implements EventLogStore {

    /** The collection name of contract rule C1. */
    public static final String COLLECTION_NAME = "octometer_events";

    private static final String OCTOMETER_RETENTION_DAYS = "OCTOMETER_RETENTION_DAYS";
    private static final String TTL_INDEX_NAME = "ts_ttl";
    private static final int DEFAULT_RETENTION_DAYS = 30;
    private static final int INDEX_OPTIONS_CONFLICT_ERROR_CODE = 85;

    /**
     * The largest retention that the TTL index field of the server
     * accepts. {@code expireAfterSeconds} is a 32-bit whole number on the
     * server, and 24855 days is the largest whole number of days whose
     * second count still fits. A value above this bound is clamped.
     */
    private static final int MAX_RETENTION_DAYS = 24855;

    /** Only an ASCII digit sets a retention. A Unicode digit does not. */
    private static final Pattern ASCII_DIGITS = Pattern.compile("[0-9]+");

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
     * process environment. A test uses this constructor. The value of
     * {@code retentionDays} must be 1 or more.
     */
    MongoEventLogStore(MongoDatabase database, int retentionDays) {
        Objects.requireNonNull(database, "database must not be null");
        if (retentionDays < 1) {
            throw new IllegalArgumentException("retentionDays must be 1 or more");
        }
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
        try {
            collection.insertMany(documents, new InsertManyOptions().ordered(true));
        } catch (MongoBulkWriteException e) {
            throw new EventLogWriteException(firstErrorCode(e));
        } catch (MongoCommandException e) {
            throw new EventLogWriteException(e.getErrorCode());
        } catch (RuntimeException e) {
            throw new EventLogWriteException(-1);
        }
    }

    /**
     * The numeric code of a failed bulk write: the code of the first
     * write error, or the code of the write concern error, or -1 when
     * neither one exists. The method reads only a numeric code. It never
     * reads a message, a name space, or a document, because each one can
     * hold a host, a port, or a stored value (design decision D15).
     */
    private static int firstErrorCode(MongoBulkWriteException exception) {
        if (!exception.getWriteErrors().isEmpty()) {
            return exception.getWriteErrors().get(0).getCode();
        }
        return exception.getWriteConcernError() == null
                ? -1
                : exception.getWriteConcernError().getCode();
    }

    /**
     * Implements {@link EventLogStore#deleteByUserId} (contract rule
     * C43). See the class comment above for the three-command shape,
     * the collection-scan cost, and the erasure order of design decision
     * D15.
     */
    @Override
    public DeletionResult deleteByUserId(String userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        if (userId.isEmpty()) {
            throw new IllegalArgumentException("userId must not be an empty text");
        }
        try {
            List<String> sessionIds = collection.distinct("sessionId", Filters.eq("userId", userId), String.class)
                    .into(new ArrayList<>());
            long userEventCount = collection.deleteMany(Filters.eq("userId", userId)).getDeletedCount();
            long anonymousEventCount = sessionIds.isEmpty() ? 0L : deleteAnonymousEvents(sessionIds);
            return new DeletionResult(userEventCount, anonymousEventCount);
        } catch (MongoCommandException e) {
            throw new EventLogDeleteException(e.getErrorCode());
        } catch (RuntimeException e) {
            throw new EventLogDeleteException(-1);
        }
    }

    /**
     * Deletes each event with {@code userId: null} whose {@code
     * sessionId} is one of {@code sessionIds} (contract rule C43).
     */
    private long deleteAnonymousEvents(List<String> sessionIds) {
        Bson filter = Filters.and(Filters.eq("userId", null), Filters.in("sessionId", sessionIds));
        DeleteResult result = collection.deleteMany(filter);
        return result.getDeletedCount();
    }

    private static Document toDocument(IngestEvent event, String userId) {
        return new Document()
                .append("ts", Date.from(event.ts()))
                .append("element", event.element())
                .append("sessionId", event.sessionId())
                .append("userId", userId);
    }

    private void ensureTtlIndex(MongoDatabase database, int retentionDays) {
        int clampedRetentionDays = clampRetentionDays(retentionDays);
        long expireAfterSeconds = (long) clampedRetentionDays * 24 * 60 * 60;
        try {
            collection.createIndex(Indexes.ascending("ts"),
                    new IndexOptions().name(TTL_INDEX_NAME).expireAfter(expireAfterSeconds, TimeUnit.SECONDS));
        } catch (MongoCommandException e) {
            if (e.getErrorCode() == INDEX_OPTIONS_CONFLICT_ERROR_CODE) {
                runCollMod(database, expireAfterSeconds);
            } else {
                warnIndexError("createIndex", e);
            }
        } catch (RuntimeException e) {
            warnIndexError("createIndex", e);
        }
    }

    private void runCollMod(MongoDatabase database, long expireAfterSeconds) {
        try {
            Document command = new Document("collMod", COLLECTION_NAME)
                    .append("index", new Document("keyPattern", new Document("ts", 1))
                            .append("expireAfterSeconds", expireAfterSeconds));
            database.runCommand(command);
        } catch (RuntimeException e) {
            warnIndexError("collMod", e);
        }
    }

    /**
     * Logs one warn-level line for a failed TTL index step. The line
     * names the step and the numeric MongoDB error code. It never holds
     * the server text, because the server text can hold a host, a port,
     * or a database name (design decision D15).
     *
     * <p>A failed {@code createIndex} step can leave the collection with
     * no TTL index at all, so every event of this app can then stay with
     * no time limit, and contract rule C8 does not apply until an
     * administrator creates the index. A failed {@code collMod} step
     * leaves the previous TTL index in place, so contract rule C8 still
     * applies, but with the old retention value.
     */
    private static void warnIndexError(String step, RuntimeException cause) {
        String errorCode = cause instanceof MongoCommandException commandException
                ? Integer.toString(commandException.getErrorCode())
                : "unknown";
        String consequence = "createIndex".equals(step)
                ? "The event collection can now have no TTL index. Each event of this "
                        + "app can then stay with no time limit. Contract rule C8 does "
                        + "not apply until an administrator creates the index."
                : "The TTL index on ts keeps its old retention value. Contract rule "
                        + "C8 applies with the old value, not the new value.";
        LOGGER.log(Level.WARNING, "The TTL index step \"" + step + "\" failed with the "
                + "MongoDB error code " + errorCode + ". The app start continues. " + consequence);
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
     * default of 30 days, with no warning. A value that holds a character
     * other than an ASCII digit, or that is not a positive whole number,
     * also gives the default, with one warning; the warning names the
     * variable and never repeats the value. A value above {@link
     * #MAX_RETENTION_DAYS} is clamped at start, with its own warning.
     */
    static int retentionDaysFromValue(String rawValue) {
        if (rawValue == null) {
            return DEFAULT_RETENTION_DAYS;
        }
        String trimmed = rawValue.trim();
        if (ASCII_DIGITS.matcher(trimmed).matches()) {
            try {
                int days = Integer.parseInt(trimmed);
                if (days > 0) {
                    return days;
                }
            } catch (NumberFormatException e) {
                // A text of only ASCII digits can still overflow an int.
                // The warning below covers this case too.
            }
        }
        LOGGER.log(Level.WARNING, OCTOMETER_RETENTION_DAYS + " holds a value that is not a positive "
                + "whole number of ASCII digits. The store uses the default of 30 days.");
        return DEFAULT_RETENTION_DAYS;
    }

    private static int clampRetentionDays(int retentionDays) {
        if (retentionDays > MAX_RETENTION_DAYS) {
            LOGGER.log(Level.WARNING, OCTOMETER_RETENTION_DAYS + " asks for " + retentionDays
                    + " days. The store clamps the value to " + MAX_RETENTION_DAYS
                    + " days, the largest value that the TTL index field of the server accepts.");
            return MAX_RETENTION_DAYS;
        }
        return retentionDays;
    }

    /**
     * A failed write to the event collection. The message holds only a
     * fixed text and the numeric MongoDB error code. It holds no host, no
     * port, and no database name, and it holds no cause, so a full stack
     * trace also stays clean (design decision D15). The error code is -1
     * when the driver gives no code.
     */
    public static final class EventLogWriteException extends RuntimeException {

        private final int errorCode;

        EventLogWriteException(int errorCode) {
            super("The store could not write the event batch to the event collection. "
                    + "The MongoDB error code is " + errorCode + ".");
            this.errorCode = errorCode;
        }

        /** The numeric MongoDB error code, or -1 when the driver gives no code. */
        public int errorCode() {
            return errorCode;
        }
    }

    /**
     * A failed {@link #deleteByUserId} call. The message holds only a
     * fixed text and the numeric MongoDB error code. It holds no host,
     * no port, no database name, no user id, and no session id, and it
     * holds no cause (design decision D15). The error code is -1 when
     * the driver gives no code.
     */
    public static final class EventLogDeleteException extends RuntimeException {

        private final int errorCode;

        EventLogDeleteException(int errorCode) {
            super("The store could not delete the events of one user id. "
                    + "The MongoDB error code is " + errorCode + ".");
            this.errorCode = errorCode;
        }

        /** The numeric MongoDB error code, or -1 when the driver gives no code. */
        public int errorCode() {
            return errorCode;
        }
    }
}
