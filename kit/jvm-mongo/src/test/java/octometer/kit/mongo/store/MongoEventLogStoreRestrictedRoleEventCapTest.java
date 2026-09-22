package octometer.kit.mongo.store;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.MongoException;
import com.mongodb.ServerAddress;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Confirms BLOCKER 2 of the security review and MAJOR 1 of the MongoDB
 * review of pull request #165: the documented minimum role of the app
 * database user ({@code insert}, {@code createIndex}, {@code collMod},
 * and {@code find} on {@code octometer_events}) lets the event cap of
 * design decision D21 work. Each test needs Docker; a machine with no
 * Docker skips the whole class.
 */
@Testcontainers(disabledWithoutDocker = true)
class MongoEventLogStoreRestrictedRoleEventCapTest {

    private static final Instant START = Instant.parse("2026-09-22T10:00:00.000Z");
    private static final String ROOT_USER = "root";
    private static final String ROOT_PASSWORD = "test-root-pass";
    private static final String APP_DATABASE = "octometer_app_test";
    private static final String APP_USER = "app";
    private static final String APP_PASSWORD = "test-app-pass";

    @Container
    private static final GenericContainer<?> MONGO = new GenericContainer<>(DockerImageName.parse("mongo:7.0"))
            .withExposedPorts(27017)
            .withEnv("MONGO_INITDB_ROOT_USERNAME", ROOT_USER)
            .withEnv("MONGO_INITDB_ROOT_PASSWORD", ROOT_PASSWORD)
            .withCommand("mongod", "--auth");

    private MongoClient rootClient;
    private MongoClient appClient;

    @BeforeEach
    void createTheDocumentedMinimumRoleUser() {
        rootClient = connectWithRetry(ROOT_USER, ROOT_PASSWORD, "admin");
        MongoDatabase appDatabaseAsRoot = rootClient.getDatabase(APP_DATABASE);
        appDatabaseAsRoot.runCommand(new Document("createRole", "octometerApp")
                .append("privileges", List.of(new Document("resource",
                                new Document("db", APP_DATABASE)
                                        .append("collection", MongoEventLogStore.COLLECTION_NAME))
                        .append("actions", List.of("insert", "createIndex", "collMod", "find"))))
                .append("roles", List.of()));
        appDatabaseAsRoot.runCommand(new Document("createUser", APP_USER)
                .append("pwd", APP_PASSWORD)
                .append("roles", List.of(new Document("role", "octometerApp").append("db", APP_DATABASE))));

        appClient = clientFor(APP_USER, APP_PASSWORD, APP_DATABASE);
    }

    @AfterEach
    void closeClients() {
        appClient.close();
        rootClient.close();
    }

    @Test
    void theDocumentedMinimumRoleLetsTheCapStopTheIngest() {
        CapturingLoggerFinder.clear();
        MongoDatabase appDatabase = appClient.getDatabase(APP_DATABASE);
        MutableClock clock = new MutableClock(START);
        // maxEvents 1: the first batch fills the cap.
        MongoEventLogStore store = new MongoEventLogStore(appDatabase, 30, 1, clock);

        store.append(List.of(new IngestEvent("s1", "e1", Instant.now())), "user-1");
        MongoCollection<Document> rawCollectionAsRoot = rootClient.getDatabase(APP_DATABASE)
                .getCollection(MongoEventLogStore.COLLECTION_NAME);
        assertEquals(1, rawCollectionAsRoot.countDocuments());

        clock.advance(Duration.ofSeconds(60));
        store.append(List.of(new IngestEvent("s1", "e2", Instant.now())), "user-1");

        assertEquals(1, rawCollectionAsRoot.countDocuments(),
                "With find granted, estimatedDocumentCount() succeeds, and the cap stops the second batch.");
        // The cap works because the count succeeded; the guard never
        // falls back to its own failure warning.
        boolean sawAFailureWarning = false;
        for (CapturingLoggerFinder.Record record : CapturingLoggerFinder.records()) {
            if (record.message().contains("could not read its event count")) {
                sawAFailureWarning = true;
            }
        }
        assertTrue(!sawAFailureWarning, "The find action must let the count succeed, with no failure warning.");
    }

    private static MongoClient connectWithRetry(String user, String password, String authDatabase) {
        MongoException lastFailure = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                MongoClient client = clientFor(user, password, authDatabase);
                client.getDatabase("admin").runCommand(new Document("ping", 1));
                return client;
            } catch (MongoException e) {
                lastFailure = e;
                sleep(1000);
            }
        }
        throw new IllegalStateException("The MongoDB container never accepted a root login.", lastFailure);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Builds a client with a {@link MongoCredential}, so no text of the
     * form {@code user:password@host} ever exists in this file (the
     * gitleaks rule {@code mongodb-uri-password} of {@code
     * .gitleaks.toml}).
     */
    private static MongoClient clientFor(String user, String password, String authDatabase) {
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyToClusterSettings(builder -> builder.hosts(
                        List.of(new ServerAddress(MONGO.getHost(), MONGO.getMappedPort(27017)))))
                .credential(MongoCredential.createCredential(user, authDatabase, password.toCharArray()))
                .build();
        return MongoClients.create(settings);
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
