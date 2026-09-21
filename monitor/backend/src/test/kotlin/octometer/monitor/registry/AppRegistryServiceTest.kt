package octometer.monitor.registry

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import octometer.monitor.store.SqliteDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
