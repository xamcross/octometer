package octometer.monitor.registry

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import octometer.monitor.store.SqliteDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Steps 2 to 5 of issue #15. Each test opens its own SqliteDatabase in a
// temporary folder, never the real data folder.
class AppRegistryServiceTest {

    private val root = Files.createTempDirectory("octometer-registry-test-").toFile()
    private val dataDir = File(root, "data").absolutePath
    private lateinit var database: SqliteDatabase
    private lateinit var secretStore: SecretStore
    private lateinit var service: AppRegistryService

    @BeforeTest
    fun setUp() {
        database = SqliteDatabase.open(dataDir)
        secretStore = SecretStore(dataDir)
        service = AppRegistryService(database, secretStore)
    }

    @AfterTest
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun `createApp inserts the app row and writes the secret, never the connection string`() = runBlocking {
        val result = service.createApp(
            CreateAppRequest("demo", allowlistedSrvUri(), "db", "octometer_events"),
        )

        val created = assertIs<CreateAppResult.Created>(result)
        assertEquals("demo", created.summary.name)
        assertTrue(secretStore.remove(created.summary.appId), "the secret must be there after create")
    }

    @Test
    fun `createApp rejects a connection string that fails the D11 check`() = runBlocking {
        val result = service.createApp(
            CreateAppRequest("demo", publicHostUri(), "db", "octometer_events"),
        )

        assertIs<CreateAppResult.InvalidRequest>(result)
        assertEquals(0, countAppRows())
    }

    @Test
    fun `createApp with a duplicate name gives NameTaken and writes no second secret`() = runBlocking {
        service.createApp(CreateAppRequest("demo", allowlistedSrvUri(), "db", "octometer_events"))

        val result = service.createApp(
            CreateAppRequest("demo", allowlistedSrvUriWithoutCredential(), "db2", "octometer_events"),
        )

        assertIs<CreateAppResult.NameTaken>(result)
        assertEquals(1, countAppRows())
    }

    @Test
    fun `updateApp changes the name`() = runBlocking {
        val created = service.createApp(
            CreateAppRequest("demo", allowlistedSrvUri(), "db", "octometer_events"),
        ) as CreateAppResult.Created

        val result = service.updateApp(created.summary.appId, UpdateAppRequest(name = "demo2"))

        assertEquals(UpdateAppResult.Updated, result)
        assertEquals("demo2", readAppName(created.summary.appId))
    }

    @Test
    fun `updateApp replaces the secret and keeps the old app row`() = runBlocking {
        val created = service.createApp(
            CreateAppRequest("demo", allowlistedSrvUri(), "db", "octometer_events"),
        ) as CreateAppResult.Created

        val result = service.updateApp(
            created.summary.appId,
            UpdateAppRequest(connectionString = allowlistedSrvUriWithoutCredential()),
        )

        assertEquals(UpdateAppResult.Updated, result)
        assertEquals("demo", readAppName(created.summary.appId))
        assertTrue(secretStore.remove(created.summary.appId))
    }

    // Issue #187: a PATCH of the connection string to a different
    // deployment must not keep the old cursor, or the reader skips
    // each older event of the new source in silence (design decision
    // D10, section 4.3).

    @Test
    fun `updateApp with a different connection string resets the poll state`() = runBlocking {
        val created = service.createApp(
            CreateAppRequest("demo", allowlistedSrvUri(), "db", "octometer_events"),
        ) as CreateAppResult.Created
        seedPollState(created.summary.appId)

        val result = service.updateApp(
            created.summary.appId,
            UpdateAppRequest(connectionString = allowlistedSrvUriWithoutCredential()),
        )

        assertEquals(UpdateAppResult.Updated, result)
        val state = readPollState(created.summary.appId)
        assertNull(state.cursor, "a different connection string must reset the cursor")
        assertNull(state.nextPollAt)
        assertNull(state.lastPollAt)
        assertNull(state.lastSuccessAt)
        assertNull(state.status)
        assertNull(state.lastError)
        assertEquals(0, state.consecutiveFailures)
    }

    @Test
    fun `updateApp with the same connection string keeps the poll state`() = runBlocking {
        val created = service.createApp(
            CreateAppRequest("demo", allowlistedSrvUri(), "db", "octometer_events"),
        ) as CreateAppResult.Created
        seedPollState(created.summary.appId)
        val before = readPollState(created.summary.appId)

        val result = service.updateApp(
            created.summary.appId,
            UpdateAppRequest(connectionString = allowlistedSrvUri()),
        )

        assertEquals(UpdateAppResult.Updated, result)
        assertEquals(before, readPollState(created.summary.appId), "the same connection string must keep the cursor")
    }

    @Test
    fun `updateApp of the name alone keeps the poll state`() = runBlocking {
        val created = service.createApp(
            CreateAppRequest("demo", allowlistedSrvUri(), "db", "octometer_events"),
        ) as CreateAppResult.Created
        seedPollState(created.summary.appId)
        val before = readPollState(created.summary.appId)

        val result = service.updateApp(created.summary.appId, UpdateAppRequest(name = "demo2"))

        assertEquals(UpdateAppResult.Updated, result)
        assertEquals(before, readPollState(created.summary.appId), "a name-only PATCH must keep the cursor")
    }

    @Test
    fun `updateApp on an unknown id gives NotFound`() = runBlocking {
        val result = service.updateApp(999L, UpdateAppRequest(name = "demo2"))

        assertEquals(UpdateAppResult.NotFound, result)
    }

    @Test
    fun `updateApp rejects a connection string that fails the D11 check, and keeps the old secret`() = runBlocking {
        val created = service.createApp(
            CreateAppRequest("demo", allowlistedSrvUri(), "db", "octometer_events"),
        ) as CreateAppResult.Created

        val result = service.updateApp(created.summary.appId, UpdateAppRequest(connectionString = publicHostUri()))

        assertIs<UpdateAppResult.InvalidRequest>(result)
        assertTrue(secretStore.remove(created.summary.appId), "the old secret must stay after a rejected update")
    }

    @Test
    fun `deleteApp removes the app row, its events, its skipped_event rows, its gap rows, and its secret`() =
        runBlocking {
            val created = service.createApp(
                CreateAppRequest("demo", allowlistedSrvUri(), "db", "octometer_events"),
            ) as CreateAppResult.Created
            val appId = created.summary.appId
            seedEvent(appId, "e1")
            seedSkippedEvent(appId, "bad-1")
            seedGap(appId)

            val found = service.deleteApp(appId)

            assertTrue(found)
            assertEquals(0, countAppRows())
            assertEquals(0, countEventRows(appId))
            assertEquals(0, countSkippedEventRows(appId))
            assertEquals(0, countGapRows(appId))
            assertFalse(secretStore.remove(appId), "the secret must be gone after delete")
        }

    @Test
    fun `deleteApp removes more events than one chunk, with a small chunk size`() = runBlocking {
        val smallChunkService = AppRegistryService(database, secretStore, eventDeleteChunkSize = 3)
        val created = service.createApp(
            CreateAppRequest("demo", allowlistedSrvUri(), "db", "octometer_events"),
        ) as CreateAppResult.Created
        val appId = created.summary.appId
        for (index in 1..7) seedEvent(appId, "e$index")

        smallChunkService.deleteApp(appId)

        assertEquals(0, countEventRows(appId))
    }

    @Test
    fun `deleteApp on an unknown id and no secret returns false`() = runBlocking {
        val found = service.deleteApp(999L)

        assertFalse(found)
    }

    @Test
    fun `a second deleteApp call finishes the work when the secret alone remains`() = runBlocking {
        val created = service.createApp(
            CreateAppRequest("demo", allowlistedSrvUri(), "db", "octometer_events"),
        ) as CreateAppResult.Created
        val appId = created.summary.appId
        // Simulates a process stop after the app row deletes but before the
        // secret deletes: the app row is gone, the secret is still there.
        database.write { writer -> writer.prepareStatement("DELETE FROM app WHERE id = ?").use {
            it.setLong(1, appId)
            it.executeUpdate()
        } }

        val found = service.deleteApp(appId)

        assertTrue(found, "the second call must still report a change, and clear the secret")
        assertFalse(secretStore.remove(appId))
    }

    @Test
    fun `a second deleteApp call finishes the work when the app row and its events still remain, but the secret is already gone`() =
        runBlocking {
            // MAJOR 4 (security review): deleteApp now removes the secret
            // FIRST. This simulates a process stop right after that step:
            // the secret is gone, the app row and its event still remain.
            val created = service.createApp(
                CreateAppRequest("demo", allowlistedSrvUri(), "db", "octometer_events"),
            ) as CreateAppResult.Created
            val appId = created.summary.appId
            seedEvent(appId, "e1")
            secretStore.remove(appId)

            val found = service.deleteApp(appId)

            assertTrue(found, "the second call must still report a change, and clear the app row")
            assertEquals(0, countAppRows())
            assertEquals(0, countEventRows(appId))
        }

    @Test
    fun `deleteApp on an id with neither an app row nor a secret changes nothing and returns false`() = runBlocking {
        val found = service.deleteApp(424_242L)

        assertFalse(found)
        assertEquals(0, countAppRows())
    }

    @Test
    fun `createApp rejects a connection string above the 2048 character limit`() = runBlocking {
        val tooLong = allowlistedSrvUriWithoutCredential("appName=" + "a".repeat(2048))

        val result = service.createApp(CreateAppRequest("demo", tooLong, "db", "octometer_events"))

        assertIs<CreateAppResult.InvalidRequest>(result)
        assertEquals(0, countAppRows())
    }

    @Test
    fun `createApp rejects a name above the 200 character limit`() = runBlocking {
        val result = service.createApp(
            CreateAppRequest("a".repeat(201), allowlistedSrvUri(), "db", "octometer_events"),
        )

        assertIs<CreateAppResult.InvalidRequest>(result)
        assertEquals(0, countAppRows())
    }

    @Test
    fun `createApp accepts the two contract collection names`() = runBlocking {
        val first = service.createApp(CreateAppRequest("demo1", allowlistedSrvUri(), "db", "octometer_events"))
        val second =
            service.createApp(CreateAppRequest("demo2", allowlistedSrvUriWithoutCredential(), "db", "octometer_events_v2"))

        assertIs<CreateAppResult.Created>(first)
        assertIs<CreateAppResult.Created>(second)
    }

    @Test
    fun `createApp rejects a collection name outside the contract`() = runBlocking {
        val result = service.createApp(CreateAppRequest("demo", allowlistedSrvUri(), "db", "system.users"))

        assertIs<CreateAppResult.InvalidRequest>(result)
        assertEquals(0, countAppRows())
    }

    @Test
    fun `CreateAppRequest toString hides the connection string`() {
        val request = CreateAppRequest("demo", allowlistedSrvUri(), "db", "octometer_events")

        assertFalse(request.toString().contains(allowlistedSrvUri()))
    }

    @Test
    fun `UpdateAppRequest toString hides the connection string`() {
        val request = UpdateAppRequest(name = "demo", connectionString = allowlistedSrvUri())

        assertFalse(request.toString().contains(allowlistedSrvUri()))
    }

    @Test
    fun `createApp removes the app row when the secret write fails, and the failure propagates`() = runBlocking {
        // MAJOR 3 (both reviews): a broken secret store must not leave an
        // orphan app row. MINOR 2 (third security review) made a plain
        // file at the secrets folder path give SecretStoreUnavailable, so
        // this test double throws a different exception, to prove the
        // cleanup still runs for a failure of any class.
        val marker = "fake-write-failure-createApp"
        val failingService = AppRegistryService(database, ThrowingSecretStore(dataDir, marker))

        val failure = assertFailsWith<IllegalStateException> {
            failingService.createApp(CreateAppRequest("demo", allowlistedSrvUri(), "db", "octometer_events"))
        }

        assertEquals(marker, failure.message)
        assertEquals(0, countAppRows())
    }

    @Test
    fun `createApp reports SecretStoreUnavailable and removes the app row when the atomic move keeps failing`() =
        runBlocking {
            val secretsDir = File(root, "secrets")
            secretsDir.mkdirs()
            File(secretsDir, "apps.json").mkdirs()

            val result = service.createApp(CreateAppRequest("demo", allowlistedSrvUri(), "db", "octometer_events"))

            assertIs<CreateAppResult.SecretStoreUnavailable>(result)
            assertEquals(0, countAppRows())
        }

    // MAJOR 2 of the second security review, and MAJOR 2 of the second
    // Ktor review: kotlinx.coroutines.CancellationException is a type
    // alias of java.util.concurrent.CancellationException. A store can
    // throw the Java class with no real cancellation of the call. The old
    // code caught that class on its own, and skipped the cleanup of
    // MAJOR 3. These tests use a store double that throws the Java class
    // directly, with one marker text, built from a constant, never from
    // a real connection string.

    @Test
    fun `createApp removes the app row when the secret store throws a Java cancellation, and the failure propagates`() =
        runBlocking {
            val marker = "fake-cancellation-createApp"
            val cancellingService = AppRegistryService(database, CancellingSecretStore(dataDir, marker))

            val thrown = assertFailsWith<java.util.concurrent.CancellationException> {
                cancellingService.createApp(CreateAppRequest("demo", allowlistedSrvUri(), "db", "octometer_events"))
            }

            assertEquals(marker, thrown.message)
            assertEquals(0, countAppRows())
        }

    @Test
    fun `updateApp propagates a Java cancellation from the secret store, instead of a SecretStoreUnavailable result`() =
        runBlocking {
            val created = service.createApp(
                CreateAppRequest("demo", allowlistedSrvUri(), "db", "octometer_events"),
            ) as CreateAppResult.Created
            val marker = "fake-cancellation-updateApp"
            val cancellingService = AppRegistryService(database, CancellingSecretStore(dataDir, marker))

            val thrown = assertFailsWith<java.util.concurrent.CancellationException> {
                cancellingService.updateApp(
                    created.summary.appId,
                    UpdateAppRequest(connectionString = allowlistedSrvUriWithoutCredential()),
                )
            }

            assertEquals(marker, thrown.message)
        }

    private suspend fun seedEvent(appId: Long, eventId: String) {
        database.write { writer ->
            writer.prepareStatement(
                "INSERT INTO event (app_id, event_id, ts, element, session_id, user_id) VALUES (?, ?, ?, ?, ?, ?)",
            ).use { insert ->
                insert.setLong(1, appId)
                insert.setString(2, eventId)
                insert.setLong(3, 1_700_000_000_000L)
                insert.setString(4, "checkout.save")
                insert.setString(5, "session-1")
                insert.setString(6, "user-1")
                insert.executeUpdate()
            }
        }
    }

    private suspend fun seedSkippedEvent(appId: Long, eventId: String) {
        database.write { writer ->
            writer.prepareStatement(
                "INSERT INTO skipped_event (app_id, event_id, reason) VALUES (?, ?, ?)",
            ).use { insert ->
                insert.setLong(1, appId)
                insert.setString(2, eventId)
                insert.setString(3, "bad shape")
                insert.executeUpdate()
            }
        }
    }

    private suspend fun seedGap(appId: Long) {
        database.write { writer ->
            writer.prepareStatement(
                "INSERT INTO gap (app_id, from_ts, to_ts) VALUES (?, ?, ?)",
            ).use { insert ->
                insert.setLong(1, appId)
                insert.setLong(2, 1L)
                insert.setLong(3, 2L)
                insert.executeUpdate()
            }
        }
    }

    private suspend fun countAppRows(): Int = countRows("SELECT COUNT(*) FROM app")

    private suspend fun countEventRows(appId: Long): Int = countRowsForApp("event", appId)

    private suspend fun countSkippedEventRows(appId: Long): Int = countRowsForApp("skipped_event", appId)

    private suspend fun countGapRows(appId: Long): Int = countRowsForApp("gap", appId)

    private suspend fun countRowsForApp(table: String, appId: Long): Int =
        database.read { reader ->
            reader.prepareStatement("SELECT COUNT(*) FROM $table WHERE app_id = ?").use { select ->
                select.setLong(1, appId)
                select.executeQuery().use { result ->
                    result.next()
                    result.getInt(1)
                }
            }
        }

    private suspend fun countRows(sql: String): Int =
        database.read { reader ->
            reader.createStatement().use { statement ->
                statement.executeQuery(sql).use { result ->
                    result.next()
                    result.getInt(1)
                }
            }
        }

    private suspend fun readAppName(appId: Long): String? =
        database.read { reader ->
            reader.prepareStatement("SELECT name FROM app WHERE id = ?").use { select ->
                select.setLong(1, appId)
                select.executeQuery().use { result ->
                    if (result.next()) result.getString(1) else null
                }
            }
        }

    // Issue #187: fills each poll-state column with a fixed value, so a
    // test of updateApp can tell a reset from a keep. The values here
    // hold no real deployment; SEEDED_CURSOR is a plain ObjectId hex.
    private suspend fun seedPollState(appId: Long) {
        database.write { writer ->
            writer.prepareStatement(
                "UPDATE app SET cursor = ?, next_poll_at = ?, last_poll_at = ?, last_success_at = ?, " +
                    "status = ?, last_error = ?, consecutive_failures = ? WHERE id = ?",
            ).use { update ->
                update.setString(1, SEEDED_CURSOR)
                update.setLong(2, SEEDED_TIMESTAMP + 60_000)
                update.setLong(3, SEEDED_TIMESTAMP)
                update.setLong(4, SEEDED_TIMESTAMP)
                update.setString(5, "OK")
                update.setString(6, "MongoReadFailedException")
                update.setInt(7, 3)
                update.setLong(8, appId)
                update.executeUpdate()
            }
        }
    }

    private suspend fun readPollState(appId: Long): PollState =
        database.read { reader ->
            reader.prepareStatement(
                "SELECT cursor, next_poll_at, last_poll_at, last_success_at, status, last_error, " +
                    "consecutive_failures FROM app WHERE id = ?",
            ).use { select ->
                select.setLong(1, appId)
                select.executeQuery().use { result ->
                    check(result.next()) { "No app row for $appId." }
                    PollState(
                        cursor = result.getString(1),
                        nextPollAt = result.getNullableLong(2),
                        lastPollAt = result.getNullableLong(3),
                        lastSuccessAt = result.getNullableLong(4),
                        status = result.getString(5),
                        lastError = result.getString(6),
                        consecutiveFailures = result.getInt(7),
                    )
                }
            }
        }

    private fun java.sql.ResultSet.getNullableLong(column: Int): Long? {
        val value = getLong(column)
        return if (wasNull()) null else value
    }
}

private const val SEEDED_CURSOR = "507f1f77bcf86cd799439011"
private const val SEEDED_TIMESTAMP = 1_700_000_000_000L

/** The seven poll-state columns of the `app` row (design decision D10, issue #187). */
private data class PollState(
    val cursor: String?,
    val nextPollAt: Long?,
    val lastPollAt: Long?,
    val lastSuccessAt: Long?,
    val status: String?,
    val lastError: String?,
    val consecutiveFailures: Int,
)

// A test double of the second security review: put() always throws the
// exact class java.util.concurrent.CancellationException, with no real
// cancellation of the calling coroutine. This is the type that a Future
// or an executor task inside a real store can throw.
private class CancellingSecretStore(dataDir: String, private val marker: String) : SecretStore(dataDir) {
    override suspend fun put(appId: Long, connectionString: String): Unit =
        throw java.util.concurrent.CancellationException(marker)
}

// A test double for MINOR 2 of the third security review: put() throws a
// plain IllegalStateException, so a test can prove the cleanup of MAJOR 3
// runs for a failure that is not SecretStoreUnavailableException.
private class ThrowingSecretStore(dataDir: String, private val marker: String) : SecretStore(dataDir) {
    override suspend fun put(appId: Long, connectionString: String): Unit =
        throw IllegalStateException(marker)
}
