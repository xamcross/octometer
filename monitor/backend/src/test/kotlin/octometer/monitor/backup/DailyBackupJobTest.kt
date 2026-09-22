package octometer.monitor.backup

import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import octometer.monitor.store.SqliteDatabase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Step 2 of issue #55: the daily backup job of MonitorServices. Every test
// here uses a fixed Clock. No test waits for a real day, and no test
// sleeps to wait for the job loop.
class DailyBackupJobTest {

    private val root = Files.createTempDirectory("octometer-daily-backup-job-test-").toFile()
    private val dataDir = File(root, "data").absolutePath

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `runOnce writes the daily backup file and prunes to the retained count`() = runBlocking {
        val database = SqliteDatabase.open(dataDir)
        try {
            val clock = Clock.fixed(Instant.parse("2026-01-08T09:00:00Z"), ZoneOffset.UTC)
            val backupsDir = backupsDir(dataDir)
            for (day in 1..7) {
                File(backupsDir, "octometer-2026010$day.db").apply { parentFile.mkdirs() }.writeText("old")
            }

            val job = DailyBackupJob(database, dataDir, clock, retainedCount = 3)
            job.runOnce()

            // A fresh database also holds a pre-migrate backup file (issue
            // #55, step 4). The daily prune must never touch it.
            val remainingDailyFiles = backupsDir.listFiles()!!
                .map { it.name }
                .filter { it.startsWith("octometer-") }
                .sorted()
            assertEquals(
                listOf("octometer-20260106.db", "octometer-20260107.db", "octometer-20260108.db"),
                remainingDailyFiles,
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun `start runs one backup at once, because no backup ran yet`() = runBlocking {
        val database = SqliteDatabase.open(dataDir)
        try {
            val clock = Clock.fixed(Instant.parse("2026-01-08T09:00:00Z"), ZoneOffset.UTC)
            val job = DailyBackupJob(database, dataDir, clock, tickInterval = Duration.ofMillis(20))
            job.start()
            try {
                val backupFile = File(backupsDir(dataDir), "octometer-20260108.db")
                awaitFile(backupFile)
                assertTrue(backupFile.isFile, "the first tick writes the daily backup")
            } finally {
                job.stop()
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `stop waits for a backup that is in progress, and leaves no partial file under the final name`() =
        runBlocking {
            val database = SqliteDatabase.open(dataDir)
            try {
                val clock = Clock.fixed(Instant.parse("2026-01-08T09:00:00Z"), ZoneOffset.UTC)
                val backupStarted = CountDownLatch(1)
                val releaseBackup = CountDownLatch(1)
                val slowAction: (Connection, File, String) -> File = { connection, dir, name ->
                    backupStarted.countDown()
                    releaseBackup.await(5, TimeUnit.SECONDS)
                    DatabaseBackup.writeTo(connection, dir, name)
                }
                val job = DailyBackupJob(
                    database = database,
                    dataDir = dataDir,
                    clock = clock,
                    tickInterval = Duration.ofMillis(20),
                    backupAction = slowAction,
                )

                job.start()
                assertTrue(backupStarted.await(5, TimeUnit.SECONDS), "the backup started")

                val backupFile = File(backupsDir(dataDir), "octometer-20260108.db")
                assertTrue(!backupFile.isFile, "the backup is still in progress, thus no final file exists yet")

                releaseBackup.countDown()
                job.stop()

                assertTrue(backupFile.isFile, "stop waited for the in-progress backup to finish")
                val leftoverTempFiles = backupsDir(dataDir).listFiles { file -> file.name.endsWith(".tmp") }
                assertTrue(leftoverTempFiles.isNullOrEmpty(), "no temporary file stays after stop")
            } finally {
                database.close()
            }
        }

    @Test
    fun `stop cancels at once, when no backup is in progress`() = runBlocking {
        val database = SqliteDatabase.open(dataDir)
        try {
            val clock = Clock.fixed(Instant.parse("2026-01-08T09:00:00Z"), ZoneOffset.UTC)
            val job = DailyBackupJob(database, dataDir, clock, tickInterval = Duration.ofSeconds(60))
            job.start()
            awaitFile(File(backupsDir(dataDir), "octometer-20260108.db"))

            val before = System.nanoTime()
            job.stop()
            val elapsedMillis = (System.nanoTime() - before) / 1_000_000
            assertTrue(elapsedMillis < 5_000, "stop does not wait for the next tick")
        } finally {
            database.close()
        }
    }

    private fun awaitFile(file: File, timeoutMillis: Long = 5_000) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (!file.isFile && System.nanoTime() < deadline) {
            Thread.sleep(20)
        }
    }
}
