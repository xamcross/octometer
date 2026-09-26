package octometer.monitor.mongo

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import octometer.kit.core.ingest.IngestEvent
import octometer.kit.mongo.store.MongoEventLogStore
import octometer.monitor.registerTempRoot
import octometer.monitor.store.EventStore
import octometer.monitor.store.SqliteDatabase
import org.bson.Document
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mongodb.MongoDBContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The container tests of the privilege check of design decision D9
 * (issue #30, a production blocker). Each test needs Docker; a
 * machine with no Docker skips the whole class, the form of
 * [MongoAppReaderContainerTest].
 *
 * [MONGO] runs the module's one image tag, `mongo:7.0`, with
 * `--auth` on through the root environment of the image entrypoint
 * (the correction of the brief review of 2026-09-26): no
 * `withReplicaSet()` and no `withCommand`. [MONGO_INITDB_ROOT_PASSWORD]
 * is a fixed environment-variable name of the image, so it is written
 * as it is; the allow-list of `.gitleaks.toml` covers the line.
 */
@Testcontainers(disabledWithoutDocker = true)
class PrivilegeCheckContainerTest {

    @Suppress("unused")
    companion object {
        private const val ROOT_USER = "octorootuser4b7d"
        private const val ROOT_SECOND_VALUE = "octorootword9c2f"

        @Container
        @JvmStatic
        private val MONGO: MongoDBContainer = MongoDBContainer(DockerImageName.parse("mongo:7.0"))
            .withEnv("MONGO_INITDB_ROOT_USERNAME", ROOT_USER)
            .withEnv("MONGO_INITDB_ROOT_PASSWORD", ROOT_SECOND_VALUE)
    }

    // A fresh database name for each test (the form of
    // MongoAppReaderContainerTest): every test method of this class
    // shares one MONGO container, so a fixed name would leak state
    // between tests, for example the collection that one test's
    // insertRootEvent creates.
    private val databaseName = "octometer_privcheck_" + System.nanoTime()
    private val collectionName = "octometer_events"
    private val otherCollectionName = "other_events"

    private val root = Files.createTempDirectory("octometer-privilege-check-container-test-")
        .toFile().also { registerTempRoot(it) }
    private val dataDir = File(root, "data")
    private lateinit var sqlite: SqliteDatabase
    private lateinit var eventStore: EventStore
    private lateinit var reader: MongoAppReader
    private lateinit var rootClient: MongoClient
    private var appId: Long = 0

    @BeforeTest
    fun setUp() = runBlocking {
        sqlite = SqliteDatabase.open(dataDir.absolutePath)
        eventStore = EventStore(sqlite)
        reader = MongoAppReader(eventStore, settleLagSeconds = 1)
        appId = insertApp(sqlite, databaseName, collectionName)
        rootClient = MongoClients.create(rootConnectionString())
    }

    @AfterTest
    fun tearDown() {
        reader.close()
        rootClient.close()
        sqlite.close()
        root.deleteRecursively()
    }

    // The acceptance criterion "the correct user gives OK, also when
    // the collection does not exist yet."
    @Test
    fun `the correct user gives OK before the collection exists`() = runBlocking {
        val userName = "octofinduser5a1e"
        val userSecondValue = "octofindword5a1e"
        createFindOnlyRoleAndUser(userName, userSecondValue, collectionName)

        val outcome = reader.pollOnce(privilegeCheckTarget(), userConnectionString(userName, userSecondValue))

        assertEquals(0, outcome.eventsStored, "the collection does not exist yet")
        assertEquals("OK", readAppStatus(sqlite, appId))
    }

    // The acceptance criteria "a readWrite user and a database-wide
    // read user each give OVERPRIVILEGED, and the next cycles read no
    // event", and "after the role is corrected, the next cycle gives
    // OK and reads the events."
    @Test
    fun `a readWrite user gives OVERPRIVILEGED, reads no event, then OK after the role is corrected`() = runBlocking {
        val userName = "octoreadwriteuser7c3f"
        val userSecondValue = "octoreadwriteword7c3f"
        createBuiltinRoleUser(userName, userSecondValue, "readWrite")
        val connectionString = userConnectionString(userName, userSecondValue)
        insertRootEvent()
        settle()

        val failure = kotlin.runCatching { reader.pollOnce(privilegeCheckTarget(), connectionString) }.exceptionOrNull()
        assertTrue(failure is MongoReadFailedException, "expected a MongoReadFailedException, got $failure")
        assertEquals("OVERPRIVILEGED", failure.status)
        eventStore.recordFailure(appId, failure.status, failure.reason, nextPollAt = 1_000L, nowMillis = 1_000L)
        assertEquals("OVERPRIVILEGED", readAppStatus(sqlite, appId))
        assertEquals(0, countEvents(sqlite, appId), "a failed check reads no event")

        correctRole(userName, collectionName)

        val outcome = reader.pollOnce(privilegeCheckTarget(), connectionString)
        assertEquals(1, outcome.eventsStored, "the corrected role must read the earlier event")
        assertEquals("OK", readAppStatus(sqlite, appId))
    }

    @Test
    fun `a database-wide read user gives OVERPRIVILEGED and reads no event`() = runBlocking {
        val userName = "octoreaduser2e9a"
        val userSecondValue = "octoreadword2e9a"
        createBuiltinRoleUser(userName, userSecondValue, "read")
        insertRootEvent()

        val failure = kotlin.runCatching {
            reader.pollOnce(privilegeCheckTarget(), userConnectionString(userName, userSecondValue))
        }.exceptionOrNull()

        assertTrue(failure is MongoReadFailedException, "expected a MongoReadFailedException, got $failure")
        assertEquals("OVERPRIVILEGED", failure.status)
        assertEquals(0, countEvents(sqlite, appId), "a failed check reads no event")
    }

    // The acceptance criterion "a configured collection name that
    // differs from the role gives a status other than OK."
    @Test
    fun `a user with find on a different collection gives a status other than OK`() = runBlocking {
        val userName = "octootheruser6d4b"
        val userSecondValue = "octootherword6d4b"
        createFindOnlyRoleAndUser(userName, userSecondValue, otherCollectionName)

        val failure = kotlin.runCatching {
            reader.pollOnce(privilegeCheckTarget(), userConnectionString(userName, userSecondValue))
        }.exceptionOrNull()

        assertTrue(failure is MongoReadFailedException, "expected a MongoReadFailedException, got $failure")
        assertNotEquals("OK", failure.status)
    }

    private fun privilegeCheckTarget(): PollTarget = PollTarget(appId, databaseName, collectionName, cursor = null, checkPrivileges = true)

    private fun rootConnectionString(): String =
        "mongodb" + "://" + ROOT_USER + ":" + ROOT_SECOND_VALUE + "@" + MONGO.host + ":" + MONGO.getMappedPort(27017) + "/?authSource=admin"

    private fun userConnectionString(userName: String, userSecondValue: String): String =
        "mongodb" + "://" + userName + ":" + userSecondValue + "@" + MONGO.host + ":" + MONGO.getMappedPort(27017) +
            "/$databaseName?authSource=$databaseName"

    /** Creates a custom role with `find` on one collection of [databaseName], and a user of that role. */
    private fun createFindOnlyRoleAndUser(userName: String, userSecondValue: String, collection: String) {
        val roleName = "octoFindRole" + userName
        val database = rootClient.getDatabase(databaseName)
        database.runCommand(
            Document("createRole", roleName)
                .append(
                    "privileges",
                    listOf(
                        Document("resource", Document("db", databaseName).append("collection", collection))
                            .append("actions", listOf("find")),
                    ),
                )
                .append("roles", emptyList<Document>()),
        )
        createUserWithRole(database, userName, userSecondValue, roleName)
    }

    /** Creates a user with a built-in role (`readWrite` or `read`) of [databaseName]. */
    private fun createBuiltinRoleUser(userName: String, userSecondValue: String, builtinRole: String) {
        createUserWithRole(rootClient.getDatabase(databaseName), userName, userSecondValue, builtinRole)
    }

    private fun createUserWithRole(database: com.mongodb.client.MongoDatabase, userName: String, userSecondValue: String, roleName: String) {
        database.runCommand(
            Document("createUser", userName)
                .append("pwd", userSecondValue)
                .append("roles", listOf(Document("role", roleName).append("db", databaseName))),
        )
    }

    /** Grants [userName] the correct custom role, in place of its earlier over-privileged role. */
    private fun correctRole(userName: String, collection: String) {
        val roleName = "octoFindRole" + userName + "Corrected"
        val database = rootClient.getDatabase(databaseName)
        database.runCommand(
            Document("createRole", roleName)
                .append(
                    "privileges",
                    listOf(
                        Document("resource", Document("db", databaseName).append("collection", collection))
                            .append("actions", listOf("find")),
                    ),
                )
                .append("roles", emptyList<Document>()),
        )
        database.runCommand(
            Document("updateUser", userName).append("roles", listOf(Document("role", roleName).append("db", databaseName))),
        )
    }

    private fun insertRootEvent() = runBlocking {
        val store = MongoEventLogStore(rootClient.getDatabase(databaseName))
        store.append(listOf(IngestEvent(UUID.randomUUID().toString(), "nav.open", Instant.now())), "user-1")
    }

    /**
     * Waits past the 1-second lag of [reader] (the same wait as
     * [MongoAppReaderContainerTest.settle]). An ObjectId has a
     * resolution of 1 second, so an insert near the end of a server
     * second needs about 1 extra second of real wait, on top of the
     * 1-second lag, before the bound of section 4.3 is certain to
     * include it.
     */
    private suspend fun settle() {
        kotlinx.coroutines.delay(2_500)
    }
}

private suspend fun insertApp(database: SqliteDatabase, databaseName: String, collectionName: String): Long =
    database.write { writer ->
        writer.prepareStatement(
            "INSERT INTO app (name, database_name, collection_name, created_at) VALUES (?, ?, ?, ?)",
        ).use { insert ->
            insert.setString(1, "demo-" + UUID.randomUUID())
            insert.setString(2, databaseName)
            insert.setString(3, collectionName)
            insert.setLong(4, 1_700_000_000_000L)
            insert.executeUpdate()
        }
        writer.createStatement().use { statement ->
            statement.executeQuery("SELECT last_insert_rowid()").use { result ->
                result.next()
                result.getLong(1)
            }
        }
    }

private suspend fun readAppStatus(database: SqliteDatabase, appId: Long): String? =
    database.read { reader ->
        reader.prepareStatement("SELECT status FROM app WHERE id = ?").use { select ->
            select.setLong(1, appId)
            select.executeQuery().use { result ->
                result.next()
                result.getString(1)
            }
        }
    }

private suspend fun countEvents(database: SqliteDatabase, appId: Long): Int =
    database.read { reader ->
        reader.prepareStatement("SELECT COUNT(*) FROM event WHERE app_id = ?").use { select ->
            select.setLong(1, appId)
            select.executeQuery().use { result ->
                result.next()
                result.getInt(1)
            }
        }
    }
