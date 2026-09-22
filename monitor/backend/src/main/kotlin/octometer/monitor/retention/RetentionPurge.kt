package octometer.monitor.retention

import java.time.Clock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import octometer.monitor.store.SqliteDatabase
import octometer.monitor.store.SqlitePragmas
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("octometer.monitor.retention.RetentionPurge")

// Issue #59, step 2: one batch holds 10 000 rows. Each batch runs its own
// call to database.write. The one writer thread of D3 is free for a page
// commit between two batches. No single call holds the thread for the
// whole purge.
private const val PURGE_BATCH_SIZE = 10_000

private const val MILLIS_PER_DAY = 86_400_000L

// The subquery form, not "DELETE ... LIMIT". A plain DELETE with a LIMIT
// clause needs a compile-time SQLite option that the driver build may not
// carry. The subquery is the portable form of a bounded delete. No "kind"
// filter: the statement deletes a click row (kind = 0) and a session
// start row (kind = 1) alike (issue #59, "each event kind").
//
// The inner subquery scans the covering index event_session, because no
// index leads with "ts" (correction round 1, SQLite review). Measured on
// 200 000 rows: one purgeOnce call takes 816 ms with 20 000 old rows, and
// 15 ms with 0 old rows. This cost fits the event cap of D21. A later
// issue adds an index on (ts) once the store passes about 5 million rows.
private const val DELETE_BATCH_SQL = """
    DELETE FROM event WHERE rowid IN (
      SELECT rowid FROM event WHERE ts < ? LIMIT $PURGE_BATCH_SIZE
    )
"""

private const val CHECKPOINT_SQL = "PRAGMA wal_checkpoint(TRUNCATE)"
private const val NO_WAIT_SQL = "PRAGMA busy_timeout=0"
private const val RESTORE_BUSY_TIMEOUT_SQL = "PRAGMA busy_timeout=${SqlitePragmas.DEFAULT_BUSY_TIMEOUT_MILLIS}"

/**
 * The purge function of issue #59, steps 2 and 3 (D15). One call to
 * [purgeOnce] deletes each event older than `retentionDays`, counted
 * from [clock]. An event of exactly `retentionDays` age stays. The purge
 * deletes only an event older than the limit.
 *
 * The purge never changes `app.cursor`. The reader resumes each app from
 * that cursor, not from a row of the local `event` table. A deleted
 * event never reaches the store a second time.
 *
 * The delete runs in batches of [PURGE_BATCH_SIZE] rows. It checks the
 * cancel between two batches, so [RetentionPurgeJob.stop] returns soon
 * after a cancel. The purge runs [CHECKPOINT_SQL] once, after the last
 * batch, with `busy_timeout` at 0 for that one call (correction round 1,
 * decision 7). It logs the total deleted count. It never logs event
 * data.
 *
 * [onWrite] runs after each write call to the database, a delete batch
 * or the checkpoint alike. A test uses it to count the write calls. The
 * production caller never sets it.
 */
class RetentionPurge(
    private val database: SqliteDatabase,
    private val clock: Clock,
    private val onWrite: () -> Unit = {},
) {

    suspend fun purgeOnce(retentionDays: Int) {
        val cutoff = clock.millis() - retentionDays.toLong() * MILLIS_PER_DAY
        var totalDeleted = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val deleted = deleteOneBatch(cutoff)
            totalDeleted += deleted
            if (deleted < PURGE_BATCH_SIZE) break
        }
        runCheckpoint()
        logResult(totalDeleted)
    }

    private fun logResult(totalDeleted: Int) {
        if (totalDeleted == 0) {
            log.debug("The purge deleted 0 event row(s).")
        } else {
            log.info("The purge deleted {} event row(s).", totalDeleted)
        }
    }

    private suspend fun deleteOneBatch(cutoff: Long): Int {
        val deleted = database.write { writer ->
            writer.prepareStatement(DELETE_BATCH_SQL).use { delete ->
                delete.setLong(1, cutoff)
                delete.executeUpdate()
            }
        }
        onWrite()
        return deleted
    }

    // Decision 7 of correction round 1: read the answer row of the
    // checkpoint. A busy answer means a concurrent reader held a page.
    // The checkpoint then truncates less, or nothing, and the code never
    // retries with the normal busy_timeout; the next scheduled run tries
    // again instead.
    private suspend fun runCheckpoint() {
        database.write { writer ->
            writer.createStatement().use { it.execute(NO_WAIT_SQL) }
            try {
                writer.createStatement().use { statement ->
                    statement.executeQuery(CHECKPOINT_SQL).use { result ->
                        check(result.next()) { "PRAGMA wal_checkpoint(TRUNCATE) gave no row." }
                        val busy = result.getInt(1)
                        if (busy != 0) {
                            log.debug("The checkpoint was busy. It did not run to completion.")
                        }
                    }
                }
            } finally {
                writer.createStatement().use { it.execute(RESTORE_BUSY_TIMEOUT_SQL) }
            }
        }
        onWrite()
    }
}
