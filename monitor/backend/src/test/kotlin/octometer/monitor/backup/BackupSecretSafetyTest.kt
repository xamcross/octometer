package octometer.monitor.backup

import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import octometer.monitor.registerTempRoot
import octometer.monitor.registry.AppRegistryService
import octometer.monitor.registry.CreateAppRequest
import octometer.monitor.registry.CreateAppResult
import octometer.monitor.registry.SecretStore
import octometer.monitor.registry.allowlistedSrvUri
import octometer.monitor.store.SqliteDatabase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Acceptance criterion of issue #55: a backup file holds no connection
// string. D11 keeps every connection string in secrets/apps.json, outside
// dataDir, thus a backup of octometer.db never reads that file.
//
// Reliability MAJOR 2 of correction round 1: the old test wrote the
// connection string to the secret store file directly, never through the
// real request path. The backup then never came near a string that no
// code ever put inside dataDir, thus the assertion held for every build,
// good or bad. This test registers the app through AppRegistryService,
// the one path a real create-app request uses.
class BackupSecretSafetyTest {

    private val root = Files.createTempDirectory("octometer-backup-secret-test-").toFile().also { registerTempRoot(it) }
    private val dataDir = File(root, "data").absolutePath
    private val clock: Clock = Clock.fixed(Instant.parse("2026-01-08T09:00:00Z"), ZoneOffset.UTC)

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `the daily backup file holds no connection string of an app the registry created`() = runBlocking {
        val secretString = allowlistedSrvUri()
        val secretStore = SecretStore(dataDir)
        val database = SqliteDatabase.open(dataDir, clock = clock)
        try {
            val registry = AppRegistryService(database, secretStore)
            val result = registry.createApp(
                CreateAppRequest(
                    name = "probe",
                    connectionString = secretString,
                    database = "exampledb",
                    collection = "octometer_events",
                ),
            )
            assertTrue(result is CreateAppResult.Created, "the app is created")

            val job = DailyBackupJob(database, dataDir, clock)
            job.runOnce()

            val backupFile = File(backupsDir(dataDir), dailyBackupFileName(clock))
            assertTrue(backupFile.isFile, "the daily backup file exists")
            val backupBytes = backupFile.readText(Charsets.ISO_8859_1)
            assertFalse(
                backupBytes.contains(secretString),
                "the backup file text does not hold the connection string",
            )

            // A byte search of the live database file too, so a future
            // change that writes the secret straight into a SQLite column
            // fails here, before it ever reaches a backup.
            val liveBytes = File(dataDir, "octometer.db").readText(Charsets.ISO_8859_1)
            assertFalse(
                liveBytes.contains(secretString),
                "the live database file text does not hold the connection string",
            )
        } finally {
            database.close()
        }
    }
}
