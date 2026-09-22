package octometer.monitor.backup

import java.io.File
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import octometer.monitor.store.SqliteDatabase
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("octometer.monitor.backup.DailyBackupJob")

/** The count of daily backup files that the job keeps (step 3 of issue #55). */
const val RETAINED_DAILY_BACKUPS = 7
private val DEFAULT_TICK_INTERVAL: Duration = Duration.ofMinutes(1)

// Reliability MAJOR 1 of correction round 1: a failed run must try again
// at the next tick, not wait for the next day. This bounds the ERROR log
// line to at most one for each hour, so a permanent failure never floods
// the log.
private val WARNING_THROTTLE: Duration = Duration.ofHours(1)

/**
 * The daily backup job of D36. [MonitorServices] starts one instance at
 * `open`, and stops it at `close`. The job wakes on [tickInterval], and it
 * writes one backup on the first wake, and again once the calendar day of
 * [clock] changes. `stop` waits for a backup that runs, so no partial
 * file ever stays under the final name.
 *
 * [backupDir] names the folder of the daily backup file. It defaults to
 * the sibling folder `backups` of [dataDir]; [octometer.monitor.MonitorServices]
 * gives the resolved value of the config key `backupDir` (issue #55,
 * SQLite MAJOR 1 of correction round 1).
 *
 * [backupAction] defaults to [DatabaseBackup.writeTo]. A test gives its
 * own function, so it can hold the job in the middle of a backup.
 */
class DailyBackupJob(
    private val database: SqliteDatabase,
    private val dataDir: String,
    private val clock: Clock,
    private val backupDir: String = backupsDir(dataDir).absolutePath,
    private val retainedCount: Int = RETAINED_DAILY_BACKUPS,
    private val tickInterval: Duration = DEFAULT_TICK_INTERVAL,
    private val backupAction: (Connection, File, String) -> File = DatabaseBackup::writeTo,
) {

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.Default)
    private var loopJob: Job? = null

    // Reliability MAJOR 1: the loop sets this only after a success, never
    // after a failure, so a failed day tries again at the next tick.
    @Volatile
    private var lastBackupDate: LocalDate? = null

    @Volatile
    private var lastWarningInstant: Instant? = null

    /** Starts the wake loop. Call this at most one time. */
    fun start() {
        // SQLite MAJOR 4 of correction round 1: a crash of an earlier run
        // can leave a temporary file in the folder. The sweep at the
        // start removes it, so the folder never grows without a limit.
        sweepStaleTempFiles(File(backupDir))
        loopJob = scope.launch { loop() }
    }

    /**
     * Stops the wake loop. It waits for a backup that is in progress, so
     * the caller never sees a half-written file. It returns at once when
     * no backup runs.
     */
    suspend fun stop() {
        loopJob?.cancelAndJoin()
        supervisor.cancel()
    }

    /**
     * Writes one backup now, then prunes to [retainedCount] files. Public
     * for a direct test.
     *
     * Reliability MINOR 1 (the maintainer decision after correction round
     * 1): the job never writes over an existing backup file. A file of
     * the exact target name already there means a backup of that day
     * already ran, so this skips the write, with one log line.
     */
    suspend fun runOnce() {
        val dir = File(backupDir)
        val name = dailyBackupFileName(clock)
        val finalFile = File(dir, name)
        if (finalFile.isFile) {
            log.info("The daily backup skipped {}, because that file exists already.", finalFile.name)
            return
        }
        // SQLite MINOR 10 of the SQLite and file system review: this runs
        // on the writer thread, so it blocks each write for the length of
        // the backup: 192 ms for 200 000 rows, one time each day. That
        // cost stays acceptable at the size of the owner. A later issue
        // can give the backup its own connection, off the writer thread.
        val file = database.write { connection -> backupAction(connection, dir, name) }
        pruneDailyBackups(dir, retainedCount)
        // SQLite MAJOR 4: the daily prune also sweeps a stale temporary
        // file, so a crash on an earlier day never survives a full day.
        sweepStaleTempFiles(dir)
        // The log line holds the file name and the size, and no folder
        // path (lesson 3 of the backend brief).
        log.info("The daily backup wrote {} ({} bytes).", file.name, file.length())
    }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            if (isDailyBackupDue(clock, lastBackupDate)) {
                try {
                    runOnce()
                    lastBackupDate = LocalDate.now(clock)
                } catch (cancellation: CancellationException) {
                    // A real cancellation must reach the caller of stop(),
                    // so it never counts as a failed backup here (lesson
                    // 2 of the backend brief).
                    if (!currentCoroutineContext().isActive) throw cancellation
                    warnOnThrottle(cancellation.javaClass.simpleName)
                } catch (failure: Exception) {
                    // lastBackupDate stays at its old value, so the next
                    // tick tries again, with no wait for a new day.
                    warnOnThrottle(failure.javaClass.simpleName)
                }
            }
            delay(tickInterval.toMillis())
        }
    }

    private fun warnOnThrottle(failureClassName: String) {
        val now = Instant.now(clock)
        val lastWarning = lastWarningInstant
        if (lastWarning == null || Duration.between(lastWarning, now) >= WARNING_THROTTLE) {
            log.error("The daily backup failed. {}", failureClassName)
            lastWarningInstant = now
        }
    }
}
