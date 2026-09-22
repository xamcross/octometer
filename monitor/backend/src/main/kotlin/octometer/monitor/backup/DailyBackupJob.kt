package octometer.monitor.backup

import java.io.File
import java.sql.Connection
import java.time.Clock
import java.time.Duration
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

/**
 * The daily backup job of D36. [MonitorServices] starts one instance at
 * `open`, and stops it at `close`. The job wakes on [tickInterval], and it
 * writes one backup on the first wake, and again once the calendar day of
 * [clock] changes. `stop` waits for a backup that runs, so no partial
 * file ever stays under the final name.
 *
 * [backupAction] defaults to [DatabaseBackup.writeTo]. A test gives its
 * own function, so it can hold the job in the middle of a backup.
 */
class DailyBackupJob(
    private val database: SqliteDatabase,
    private val dataDir: String,
    private val clock: Clock,
    private val retainedCount: Int = RETAINED_DAILY_BACKUPS,
    private val tickInterval: Duration = DEFAULT_TICK_INTERVAL,
    private val backupAction: (Connection, File, String) -> File = DatabaseBackup::writeTo,
) {

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.Default)
    private var loopJob: Job? = null

    @Volatile
    private var lastBackupDate: LocalDate? = null

    /** Starts the wake loop. Call this at most one time. */
    fun start() {
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

    /** Writes one backup now, then prunes to [retainedCount] files. Public for a direct test. */
    suspend fun runOnce() {
        val dir = backupsDir(dataDir)
        val name = dailyBackupFileName(clock)
        val file = database.write { connection -> backupAction(connection, dir, name) }
        pruneDailyBackups(dir, retainedCount)
        // The log line holds the file name only, never a folder path.
        log.info("The daily backup wrote {}.", file.name)
    }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            if (isDailyBackupDue(clock, lastBackupDate)) {
                // A real cancellation must reach the caller of stop(), so
                // it never counts as a failed backup here.
                try {
                    runOnce()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    log.error("The daily backup failed. {}", failure.javaClass.simpleName)
                }
                lastBackupDate = LocalDate.now(clock)
            }
            delay(tickInterval.toMillis())
        }
    }
}
