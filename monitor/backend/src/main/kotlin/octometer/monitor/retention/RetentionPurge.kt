package octometer.monitor.retention

import octometer.monitor.store.SqliteDatabase
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("octometer.monitor.retention.RetentionPurge")

// Issue #59, step 2: one batch holds 10 000 rows. Each batch runs inside
// its own call to database.write, so the one writer thread of D3 is free
// for a page commit between two batches, and no single call holds the
// thread for the whole purge.
private const val PURGE_BATCH_SIZE = 10_000

private const val MILLIS_PER_DAY = 86_400_000L

// The subquery form, not "DELETE ... LIMIT", because a plain DELETE with
// a LIMIT clause needs a compile-time SQLite option that the driver build
// may not carry. The subquery is the portable form of a bounded delete.
// No "kind" filter: the same statement deletes a click row (kind = 0) and
// a session start row (kind = 1) alike (issue #59, "each event kind").
private const val DELETE_BATCH_SQL = """
    DELETE FROM event WHERE rowid IN (
      SELECT rowid FROM event WHERE ts < ? LIMIT $PURGE_BATCH_SIZE
    )
"""

private const val CHECKPOINT_SQL = "PRAGMA wal_checkpoint(TRUNCATE)"

/**
 * The purge function of issue #59, steps 2 and 3 (D15). One call to
 * [purgeOnce] deletes each event older than `retentionDays`, counted from
 * [clock]. An event of exactly `retentionDays` age is not "older than"
 * the limit, so the purge keeps it.
 *
 * The purge never changes `app.cursor`. The reader resumes each app from
 * that cursor, not from a row of the local `event` table, so a deleted
 * event never reaches the store a second time.
 *
 * The delete runs in batches of [PURGE_BATCH_SIZE] rows. It runs
 * [CHECKPOINT_SQL] once, after the last batch. It logs the total deleted
 * count, and no event data.
 */
class RetentionPurge(private val database: SqliteDatabase, private val clock: Clock) {

    suspend fun purgeOnce(retentionDays: Int) {
        val cutoff = clock.nowMillis() - retentionDays.toLong() * MILLIS_PER_DAY
        var totalDeleted = 0
        while (true) {
            val deleted = deleteOneBatch(cutoff)
            totalDeleted += deleted
            if (deleted < PURGE_BATCH_SIZE) break
        }
        database.write { writer ->
            writer.createStatement().use { it.execute(CHECKPOINT_SQL) }
        }
        log.info("The purge deleted {} event row(s).", totalDeleted)
    }

    private suspend fun deleteOneBatch(cutoff: Long): Int =
        database.write { writer ->
            writer.prepareStatement(DELETE_BATCH_SQL).use { delete ->
                delete.setLong(1, cutoff)
                delete.executeUpdate()
            }
        }
}
