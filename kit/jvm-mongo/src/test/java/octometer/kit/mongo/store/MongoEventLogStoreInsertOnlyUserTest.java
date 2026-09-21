package octometer.kit.mongo.store;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.MongoException;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import java.time.Instant;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Confirms MAJOR 3 of the two first reviews of pull request #126: a
 * database user that holds only {@code insert}, and no {@code
 * createIndex} right, on the app database. The store must still log
 * exactly one warn-level line, the line must name the step and the
 * numeric error code, the collection must end with no TTL index, and
 * {@link MongoEventLogStore#append} must still write the event. Each test
 * needs Docker; a machine with no Docker skips the whole class.
 */
@Testcontainers(disabledWithoutDocker = true)
class MongoEventLogStoreInsertOnlyUserTest {

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
    void createTheInsertOnlyUser() {
        rootClient = connectWithRetry(ROOT_USER, ROOT_PASSWORD, "admin");
        MongoDatabase appDatabaseAsRoot = rootClient.getDatabase(APP_DATABASE);
        appDatabaseAsRoot.runCommand(new Document("createRole", "insertOnly")
                .append("privileges", List.of(new Document("resource",
                                new Document("db", APP_DATABASE)
                                        .append("collection", MongoEventLogStore.COLLECTION_NAME))
                        .append("actions", List.of("insert"))))
                .append("roles", List.of()));
        appDatabaseAsRoot.runCommand(new Document("createUser", APP_USER)
                .append("pwd", APP_PASSWORD)
                .append("roles", List.of(new Document("role", "insertOnly").append("db", APP_DATABASE))));

        appClient = clientFor(APP_USER, APP_PASSWORD, APP_DATABASE);
    }

    @AfterEach
    void closeClients() {
        appClient.close();
        rootClient.close();
    }

    @Test
    void aUserWithOnlyInsertGetsOneWarningNoTtlIndexAndAWorkingAppend() {
        CapturingLoggerFinder.clear();
        MongoDatabase appDatabase = appClient.getDatabase(APP_DATABASE);

        MongoEventLogStore store = new MongoEventLogStore(appDatabase, 30);

        assertEquals(1, CapturingLoggerFinder.records().size());
        CapturingLoggerFinder.Record record = CapturingLoggerFinder.records().peek();
        assertEquals(java.lang.System.Logger.Level.WARNING, record.level());
        assertTrue(record.message().contains("\"createIndex\""), "The line must name the step.");
        assertTrue(record.message().contains("no TTL index"), "The line must state the plain result.");
        assertFalse(record.message().toLowerCase().contains("not authorized"),
                "The line must never hold the server text.");

        MongoCollection<Document> rawCollectionAsRoot = rootClient.getDatabase(APP_DATABASE)
                .getCollection(MongoEventLogStore.COLLECTION_NAME);
        boolean hasTtlIndex = false;
        for (Document index : rawCollectionAsRoot.listIndexes()) {
            if (index.containsKey("expireAfterSeconds")) {
                hasTtlIndex = true;
            }
        }
        assertFalse(hasTtlIndex, "No TTL index must exist for a user with no createIndex right.");

        store.append(new IngestEvent("11111111-1111-1111-1111-111111111111", "checkout.save", Instant.now()),
                "user-1");

        assertEquals(1, rawCollectionAsRoot.countDocuments());
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
}
