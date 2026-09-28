package octometer.monitor.retention

import java.time.Clock
import octometer.monitor.store.DEFAULT_BUSY_TIMEOUT_MILLIS
import octometer.monitor.store.SqliteDatabase
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
private const val RESTORE_BUSY_TIMEOUT_SQL = "PRAGMA busy_timeout=$DEFAULT_BUSY_TIMEOUT_MILLIS"

// Issue #205, the aging rule of the correction round (2026-09-27): once
// the purge above has removed every event row of a path, that value
// gives the reader's cap no further protection; it only holds a stale
// slot. This deletes each app_path row that no event row of its own
// app still holds, one app at a time (design decision D5).
//
// BLOCKER 1 of the Kotlin and SQLite review of pull request #232: a
// correlated "NOT EXISTS" subquery here has no index to use, because
// event_first_page is partial (WHERE kind = 1) and this statement must
// not add a "kind = 1" term (a click row holds a path too, and D5
// counts each row, not each start row). Each correlated run then reads
// the app's event rows one time for each app_path row: 5 305 ms with
// 50 000 events and 400 rows, on the one writer thread.
//
// The "NOT IN" form builds the path list one time, so the plan reads
// the app's events one time in total, no matter the app_path row
// count: 35.8 ms in the same probe, 148 times faster. "path IS NOT
// NULL" is necessary: one NULL in the list turns "NOT IN" itself into
// NULL for every row, and the statement would then delete no row.
private const val SELECT_APP_PATH_APP_IDS_SQL = "SELECT DISTINCT app_id FROM app_path"
internal const val DELETE_STALE_APP_PATH_SQL = """
    DELETE FROM app_path WHERE app_id = ? AND path NOT IN (
      SELECT path FROM event WHERE app_id = ? AND path IS NOT NULL
    )
"""

// Issue #227: a gap row (issue #62, D16) names a range with no events in
// the store. Once its to_ts is older than the purge bound, the range has
// no protective value: the level 1 view need not still show it. The
// table gap holds one row for each off-time of each app, so it stays
// small; one DELETE per app needs no batch loop, unlike the event purge
// above. The unit of to_ts is the same epoch millisecond unit as
// event.ts and created_at.
private const val SELECT_GAP_APP_IDS_SQL = "SELECT DISTINCT app_id FROM gap"
internal const val DELETE_OLD_GAP_SQL = "DELETE FROM gap WHERE app_id = ? AND to_ts < ?"

// Issue #301, D52, D58: the sign-in token and the session purge. A
// signin_token row is used or expired for at least one day before this
// removes it (the store keeps a short window of evidence); an expired
// session row goes at each run, with no such grace period.
internal const val DELETE_OLD_SIGNIN_TOKENS_SQL =
    "DELETE FROM signin_token WHERE (used_at IS NOT NULL OR expires_at < ?) AND created_at < ?"
internal const val DELETE_EXPIRED_SESSIONS_SQL = "DELETE FROM session WHERE expires_at < ?"

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
 * After the event batches, [purgeOnce] also deletes each `app_path`
 * row that no `event` row of its own app still holds (issue #205, the
 * aging rule of the correction round of 2026-09-27). A stale value
 * then stops holding a slot of `pathValueCap`, once every event of
 * that value has aged out.
 *
 * [purgeOnce] also deletes each `gap` row (issue #62, D16) whose
 * `to_ts` is older than the same purge bound (issue #227). A gap row
 * of exactly `retentionDays` age stays, the same boundary rule as an
 * event row.
 *
 * The delete runs in batches of [PURGE_BATCH_SIZE] rows. The write call
 * of each batch checks the cancel state on entry, so
 * [RetentionPurgeJob.stop] returns soon after a cancel. The purge runs
 * [CHECKPOINT_SQL] once, after the last batch, with `busy_timeout` at 0
 * for that one call (correction round 1, decision 7). It logs the total
 * deleted count. It never logs event
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
            // Correction round 2, MINOR A: this loop has no explicit
            // cancel check. deleteOneBatch calls database.write, and
            // database.write calls withContext. withContext checks the
            // cancel state on entry, before it runs the block. A cancel
            // between two batches still stops the loop before a further
            // batch runs.
            val deleted = deleteOneBatch(cutoff)
            totalDeleted += deleted
            if (deleted < PURGE_BATCH_SIZE) break
        }
        purgeOldGaps(cutoff)
        purgeStaleAppPathValues()
        purgeSignInArtifacts()
        runCheckpoint()
        logResult(totalDeleted)
    }

    // Issue #301, D52, D58: runs at every purge (the start, and each 24
    // hours). It never logs the token, the session id, or the mail
    // address (D15): each statement carries a bound time only.
    private suspend fun purgeSignInArtifacts() {
        val now = clock.millis()
        val tokenCutoff = now - MILLIS_PER_DAY
        database.write { writer ->
            writer.prepareStatement(DELETE_OLD_SIGNIN_TOKENS_SQL).use { delete ->
                delete.setLong(1, now)
                delete.setLong(2, tokenCutoff)
                delete.executeUpdate()
            }
        }
        onWrite()
        database.write { writer ->
            writer.prepareStatement(DELETE_EXPIRED_SESSIONS_SQL).use { delete ->
                delete.setLong(1, now)
                delete.executeUpdate()
            }
        }
        onWrite()
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

    // Issue #227: one delete for each app that still holds a gap row.
    // The gap table stays small (one row for each off-time of each
    // app), so one DELETE per app needs no batch loop, unlike the
    // event purge above (state of the maintainer's decision).
    private suspend fun purgeOldGaps(cutoff: Long) {
        val appIds = database.read { reader ->
            reader.createStatement().use { statement ->
                statement.executeQuery(SELECT_GAP_APP_IDS_SQL).use { result ->
                    val ids = mutableListOf<Long>()
                    while (result.next()) ids += result.getLong(1)
                    ids
                }
            }
        }
        for (appId in appIds) {
            database.write { writer ->
                writer.prepareStatement(DELETE_OLD_GAP_SQL).use { delete ->
                    delete.setLong(1, appId)
                    delete.setLong(2, cutoff)
                    delete.executeUpdate()
                }
            }
            onWrite()
        }
    }

    // Issue #205, the aging rule of the correction round: one delete
    // for each app that still holds an app_path row. The purge event
    // batches above already ran, so this sees the post-purge event
    // table. A small app count keeps this cheap; the purge already
    // runs each 24 hours, not on the request path of a route.
    private suspend fun purgeStaleAppPathValues() {
        val appIds = database.read { reader ->
            reader.createStatement().use { statement ->
                statement.executeQuery(SELECT_APP_PATH_APP_IDS_SQL).use { result ->
                    val ids = mutableListOf<Long>()
                    while (result.next()) ids += result.getLong(1)
                    ids
                }
            }
        }
        for (appId in appIds) {
            database.write { writer ->
                writer.prepareStatement(DELETE_STALE_APP_PATH_SQL).use { delete ->
                    delete.setLong(1, appId)
                    delete.setLong(2, appId)
                    delete.executeUpdate()
                }
            }
            onWrite()
        }
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
                            // Correction round 2, MINOR B: WARN, not DEBUG.
                            // The root log level is INFO. A DEBUG line here
                            // stayed invisible, while an old wal frame still
                            // held a purged row until a later checkpoint.
                            // This line names no event data and no row.
                            log.warn("The checkpoint was busy. It did not run to completion.")
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
