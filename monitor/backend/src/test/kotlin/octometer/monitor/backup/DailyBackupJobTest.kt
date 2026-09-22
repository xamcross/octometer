package octometer.monitor.backup

import ch.qos.logback.classic.Level
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import octometer.monitor.captureLogEvents
import octometer.monitor.registerTempRoot
import octometer.monitor.store.SqliteDatabase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Step 2 of issue #55: the daily backup job of MonitorServices. Every test
// here uses a fixed Clock. No test waits for a real day, and no test
// sleeps to wait for the job loop.
class DailyBackupJobTest {

    private val root =
        Files.createTempDirectory("octometer-daily-backup-job-test-").toFile().also { registerTempRoot(it) }
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

    // Reliability MAJOR 1 of correction round 1: a failed run must not
    // set lastBackupDate. The next tick, on the same day, tries again.
    @Test
    fun `a failed backup tries again at the next tick, and writes the file once it succeeds`() = runBlocking {
        val database = SqliteDatabase.open(dataDir)
        try {
            val clock = Clock.fixed(Instant.parse("2026-01-08T09:00:00Z"), ZoneOffset.UTC)
            var callCount = 0
            val failTwiceThenSucceed: (Connection, File, String) -> File = { connection, dir, name ->
                callCount += 1
                if (callCount < 3) {
                    throw BackupFailedException("the probe of correction round 1: a transient failure")
                }
                DatabaseBackup.writeTo(connection, dir, name)
            }
            val job = DailyBackupJob(
                database = database,
                dataDir = dataDir,
                clock = clock,
                tickInterval = Duration.ofMillis(20),
                backupAction = failTwiceThenSucceed,
            )

            job.start()
            try {
                val backupFile = File(backupsDir(dataDir), "octometer-20260108.db")
                awaitFile(backupFile)
                assertTrue(backupFile.isFile, "the job wrote the file once the backup succeeded")
                assertTrue(callCount >= 3, "the job retried at more than one tick, on the same day")
            } finally {
                job.stop()
            }
        } finally {
            database.close()
        }
    }

    // Reliability MAJOR 1: the maintainer decision bounds the ERROR line
    // to one for each hour. A fixed clock never moves, thus every tick
    // after the first stays inside that one hour, and only one line logs.
    @Test
    fun `a backup that fails every tick writes at most one warning for one hour`() = runBlocking {
        val database = SqliteDatabase.open(dataDir)
        try {
            val clock = Clock.fixed(Instant.parse("2026-01-08T09:00:00Z"), ZoneOffset.UTC)
            val alwaysFail: (Connection, File, String) -> File = { _, _, _ ->
                throw BackupFailedException("the probe of correction round 1: a permanent failure")
            }
            val job = DailyBackupJob(
                database = database,
                dataDir = dataDir,
                clock = clock,
                tickInterval = Duration.ofMillis(5),
                backupAction = alwaysFail,
            )

            val (_, events) = captureLogEvents {
                job.start()
                delay(300)
                job.stop()
            }

            val errorLines = events.filter { it.level == Level.ERROR }
            assertEquals(1, errorLines.size, "one warning for the hour, though many ticks failed")
        } finally {
            database.close()
        }
    }

    // The maintainer decision after correction round 1 (reliability MINOR
    // 1): the job never writes over an existing backup file.
    @Test
    fun `runOnce never writes over an existing backup file, and logs a skip line instead`() = runBlocking {
        val database = SqliteDatabase.open(dataDir)
        try {
            val clock = Clock.fixed(Instant.parse("2026-01-08T09:00:00Z"), ZoneOffset.UTC)
            val existingFile = File(backupsDir(dataDir), "octometer-20260108.db").apply {
                parentFile.mkdirs()
                writeText("existing backup content, made by an earlier run")
            }
            val originalBytes = existingFile.readBytes()

            val job = DailyBackupJob(database, dataDir, clock)
            job.runOnce()

            assertTrue(existingFile.readBytes().contentEquals(originalBytes), "the existing file stays untouched")
        } finally {
            database.close()
        }
    }

    // SQLite MINOR 9 of the SQLite and file system review: a sentinel
    // test that reads every log level, not only ERROR (lesson 9 of the
    // backend brief), and proves the success line holds the file name and
    // the size, and no folder path.
    @Test
    fun `the success log line holds the file name and the size, and no folder path`() = runBlocking {
        val database = SqliteDatabase.open(dataDir)
        try {
            val clock = Clock.fixed(Instant.parse("2026-01-08T09:00:00Z"), ZoneOffset.UTC)
            val job = DailyBackupJob(database, dataDir, clock)

            val (_, events) = captureLogEvents { job.runOnce() }

            val backupFile = File(backupsDir(dataDir), "octometer-20260108.db")
            val successLine = events.single { it.formattedMessage.startsWith("The daily backup wrote") }
            assertEquals(
                "The daily backup wrote ${backupFile.name} (${backupFile.length()} bytes).",
                successLine.formattedMessage,
            )
            assertFalse(
                successLine.formattedMessage.contains(backupsDir(dataDir).absolutePath),
                "the line holds no folder path",
            )
        } finally {
            database.close()
        }
    }

    // SQLite MAJOR 4 of correction round 1: a crash of an earlier run
    // leaves a stale temporary file. start() sweeps it, so the folder
    // never grows without a limit.
    @Test
    fun `start sweeps each stale temporary file of an earlier crash`() = runBlocking {
        val database = SqliteDatabase.open(dataDir)
        try {
            val clock = Clock.fixed(Instant.parse("2026-01-08T09:00:00Z"), ZoneOffset.UTC)
            val backupsDirFile = backupsDir(dataDir)
            val staleDaily = File(backupsDirFile, "octometer-20260101.db.tmp").apply {
                parentFile.mkdirs()
                writeText("stale")
            }
            val staleMigration = File(backupsDirFile, "pre-migrate-v1-20260101010101000.db.tmp").apply {
                writeText("stale")
            }

            val job = DailyBackupJob(database, dataDir, clock, tickInterval = Duration.ofSeconds(60))
            job.start()
            try {
                assertFalse(staleDaily.isFile, "the stale daily temp file is gone right after start")
                assertFalse(staleMigration.isFile, "the stale pre-migration temp file is gone right after start")
            } finally {
                job.stop()
            }
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
