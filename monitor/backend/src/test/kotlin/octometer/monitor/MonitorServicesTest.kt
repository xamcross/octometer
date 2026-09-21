package octometer.monitor

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import octometer.monitor.registry.SecretStore
import octometer.monitor.registry.allowlistedSrvUri
import octometer.monitor.store.SqliteDatabase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MAJOR 4 of the security review: a secret whose app id has no app row
 * must not stay on the disk for ever. MonitorServices.open() sweeps it
 * away at the start, and writes one log line with the removed count.
 */
class MonitorServicesTest {

    private val root = Files.createTempDirectory("octometer-monitor-services-test-").toFile()
    private val dataDir = File(root, "data").absolutePath

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `open removes each secret whose app id has no app row, and keeps the rest`() = runBlocking {
        val appId = seedOneAppRow()
        val secretStore = SecretStore(dataDir)
        secretStore.put(appId, allowlistedSrvUri())
        secretStore.put(999_999L, allowlistedSrvUri())

        val services = MonitorServices.open(prodConfig(dataDir = dataDir))
        try {
            assertTrue(services.secretStore.contains(appId), "the secret of an existing app row must stay")
            assertFalse(services.secretStore.contains(999_999L), "the orphan secret must be gone")
        } finally {
            services.close()
        }
    }

    // MAJOR 5 (second Ktor review) and MAJOR 2 (second security review): a
    // broken secrets/apps.json must not stop the start, and it must not
    // leave the database open. open() must also never overwrite a file
    // that it could not read.
    @Test
    fun `open skips the sweep and still returns usable services, when the secret file is broken at the start`() =
        runBlocking {
            val secretsDir = File(root, "secrets").apply { mkdirs() }
            val secretsFile = File(secretsDir, "apps.json")
            val brokenBytes = "{ this is not valid json".toByteArray(Charsets.UTF_8)
            secretsFile.writeBytes(brokenBytes)

            val services = MonitorServices.open(prodConfig(dataDir = dataDir))
            try {
                assertTrue(
                    brokenBytes.contentEquals(secretsFile.readBytes()),
                    "the broken file must stay exactly as it was",
                )
            } finally {
                services.close()
            }
        }

    private suspend fun seedOneAppRow(): Long {
        val database = SqliteDatabase.open(dataDir)
        val appId = try {
            database.write { writer ->
                writer.prepareStatement(
                    "INSERT INTO app (name, database_name, collection_name, created_at) VALUES (?, ?, ?, ?)",
                ).use { insert ->
                    insert.setString(1, "demo")
                    insert.setString(2, "db")
                    insert.setString(3, "octometer_events")
                    insert.setLong(4, System.currentTimeMillis())
                    insert.executeUpdate()
                }
                writer.createStatement().use { statement ->
                    statement.executeQuery("SELECT last_insert_rowid()").use { result ->
                        result.next()
                        result.getLong(1)
                    }
                }
            }
        } finally {
            database.close()
        }
        return appId
    }
}
