package octometer.monitor.backup

import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
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
class BackupSecretSafetyTest {

    private val root = Files.createTempDirectory("octometer-backup-secret-test-").toFile()
    private val dataDir = File(root, "data").absolutePath
    private val clock: Clock = Clock.fixed(Instant.parse("2026-01-08T09:00:00Z"), ZoneOffset.UTC)

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `the daily backup file holds no connection string of the secret store`() = runBlocking {
        val secretString = allowlistedSrvUri()
        val secretStore = SecretStore(dataDir)
        secretStore.put(appId = 1L, connectionString = secretString)

        val database = SqliteDatabase.open(dataDir, clock = clock)
        try {
            val job = DailyBackupJob(database, dataDir, clock)
            job.runOnce()
        } finally {
            database.close()
        }

        val backupFile = File(backupsDir(dataDir), dailyBackupFileName(clock))
        assertTrue(backupFile.isFile, "the daily backup file exists")
        val backupBytes = backupFile.readText(Charsets.ISO_8859_1)
        assertFalse(backupBytes.contains(secretString), "the backup file text does not hold the connection string")
    }
}
