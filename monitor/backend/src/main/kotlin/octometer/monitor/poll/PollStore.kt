package octometer.monitor.poll

import java.sql.Connection
import octometer.monitor.store.SqliteDatabase
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("octometer.monitor.poll.PollStore")

/**
 * One row of the app table, read at one tick (issue #17, decision 5).
 * [status] and [consecutiveFailures] are the values before this tick's
 * cycle (issue #28, the maintainer's decision 2): [PollScheduler] reads
 * them once at the start of the tick, and a failed cycle applies the
 * failed-cycle-count rule and the backoff against these same values.
 */
data class AppRow(
    val appId: Long,
    val database: String,
    val collection: String,
    val cursor: String?,
    val nextPollAt: Long?,
    val status: String?,
    val consecutiveFailures: Int,
    /** `app.privileges_checked_at` (design decision D9, issue #30), or `null` before the first check. */
    val privilegesCheckedAt: Long? = null,
    /**
     * `app.path_patterns` (issue #226, the D5 rule of 2026-09-27): the
     * route pattern list of the app, one pattern per line, or `null`
     * when the app gives no list. [PollScheduler] splits this text into
     * [octometer.monitor.mongo.PollTarget.pathPatterns]. Design decision
     * D48 (issue #277) turns this into a cached copy of the list that the
     * app publishes; the reader writes it, and the registry writes it no
     * more.
     */
    val pathPatterns: String? = null,
    /**
     * `app.path_patterns_published_at` (design decision D48, issue
     * #277): the `publishedAt` of the config document that the reader
     * last stored, epoch milliseconds UTC, or `null` before the first
     * stored list. [PollScheduler] passes it to
     * [octometer.monitor.mongo.PollTarget], so the reader can tell a
     * fresh publish apart from the stored one, and write only on a
     * change.
     */
    val pathPatternsPublishedAt: Long? = null,
    /** `app.kit_version` (design decision D48, issue #277), or `null` before the first stored list. */
    val kitVersion: String? = null,
)

/**
 * The store seam of the poll scheduler (issue #17, decision 7, MAJOR 7 of
 * the Kotlin review). [PollScheduler] reads the app list and writes each
 * poll result through this interface, never through a concrete
 * [SqliteDatabase] call.
 *
 * [SqlitePollStore] is the production value. A virtual-time test gives an
 * in-memory fake instead, so no scheduler test mixes the virtual clock of
 * `runTest` with the real writer thread of [SqliteDatabase].
 */
interface PollStore {
    suspend fun readApps(): List<AppRow>

    /**
     * Writes a good cycle's result, guarded by [expectedCursor]: the
     * last cursor of the cycle (issue #187, MAJOR 1 of the correction
     * round of pull request #208). A PATCH of the connection string can
     * reset the app row's cursor between the read at the tick and this
     * write. The guard then changes 0 rows: a normal end, with no
     * status change.
     */
    suspend fun writeResult(appId: Long, nextPollAt: Long, cursor: String?, expectedCursor: String?)

    /**
     * Records a failed poll cycle (issue #28, design decision D8, the
     * maintainer's decision 2). [status] and [lastError] are already
     * final: [PollScheduler] applied the failed-cycle-count rule and
     * built the error text before this call. [nextPollAt] already
     * holds the backoff of decision 3 of issue #28.
     */
    suspend fun recordFailure(appId: Long, status: String?, lastError: String?, nextPollAt: Long, now: Long)
}

/**
 * The production [PollStore]. It runs each read and each write inside one
 * [SqliteDatabase] block, the same SQL text as before the seam of decision
 * 7.
 */
class SqlitePollStore(private val database: SqliteDatabase) : PollStore {

    override suspend fun readApps(): List<AppRow> =
        database.read { reader -> readAppRows(reader) }

    // A deleted app gives zero updated rows here. This never throws for
    // that case (design decision D6, issue #17). A stale cursor gives
    // zero updated rows too (issue #187, MAJOR 1); writeSuccess logs
    // that case at DEBUG level, with no app data.
    override suspend fun writeResult(appId: Long, nextPollAt: Long, cursor: String?, expectedCursor: String?) {
        database.write { writer -> writeSuccess(writer, appId, nextPollAt, cursor, expectedCursor) }
    }

    override suspend fun recordFailure(appId: Long, status: String?, lastError: String?, nextPollAt: Long, now: Long) {
        database.write { writer -> writeFailure(writer, appId, status, lastError, nextPollAt, now) }
    }
}

// MAJOR 3 of the review of pull request #313 (issue #301): a row with
// account_id NULL is an app that no account owns. The account filter
// of #301 makes such a row invisible and unreachable through the API,
// so no account can pause or delete it; the WHERE clause below also
// stops its poll cycle. The run guide states that this row waits,
// inert, for a later story to attach it to an account.
private fun readAppRows(reader: Connection): List<AppRow> =
    reader.createStatement().use { statement ->
        statement.executeQuery(
            "SELECT id, database_name, collection_name, cursor, next_poll_at, status, consecutive_failures, " +
                "privileges_checked_at, path_patterns, path_patterns_published_at, kit_version FROM app " +
                "WHERE account_id IS NOT NULL",
        ).use { result ->
            val rows = mutableListOf<AppRow>()
            while (result.next()) {
                val nextPollAt = result.getLong("next_poll_at").takeUnless { result.wasNull() }
                val privilegesCheckedAt = result.getLong("privileges_checked_at").takeUnless { result.wasNull() }
                val pathPatternsPublishedAt = result.getLong("path_patterns_published_at").takeUnless { result.wasNull() }
                rows += AppRow(
                    appId = result.getLong("id"),
                    database = result.getString("database_name"),
                    collection = result.getString("collection_name"),
                    cursor = result.getString("cursor"),
                    nextPollAt = nextPollAt,
                    status = result.getString("status"),
                    consecutiveFailures = result.getInt("consecutive_failures"),
                    privilegesCheckedAt = privilegesCheckedAt,
                    pathPatterns = result.getString("path_patterns"),
                    pathPatternsPublishedAt = pathPatternsPublishedAt,
                    kitVersion = result.getString("kit_version"),
                )
            }
            rows
        }
    }

// The guard "AND cursor IS ?" matches EventStore's own cursor write
// (issue #187, MAJOR 1). A 0-row result means a PATCH moved the
// cursor during this cycle; this is a normal end, so this method
// logs one DEBUG line, with no app id and no cursor value, and
// leaves next_poll_at as it is (the reset already cleared it).
private fun writeSuccess(writer: Connection, appId: Long, nextPollAt: Long, cursor: String?, expectedCursor: String?) {
    writer.prepareStatement(
        "UPDATE app SET next_poll_at = ?, cursor = ? WHERE id = ? AND cursor IS ?",
    ).use { update ->
        update.setLong(1, nextPollAt)
        update.setString(2, cursor)
        update.setLong(3, appId)
        update.setString(4, expectedCursor)
        if (update.executeUpdate() == 0) {
            log.debug("The poll result write changed no row. The cursor moved during this cycle.")
        }
    }
}

private fun writeFailure(
    writer: Connection,
    appId: Long,
    status: String?,
    lastError: String?,
    nextPollAt: Long,
    now: Long,
) {
    writer.prepareStatement(
        "UPDATE app SET status = ?, last_error = ?, consecutive_failures = consecutive_failures + 1, " +
            "last_poll_at = ?, next_poll_at = ? WHERE id = ?",
    ).use { update ->
        update.setString(1, status)
        update.setString(2, lastError)
        update.setLong(3, now)
        update.setLong(4, nextPollAt)
        update.setLong(5, appId)
        update.executeUpdate()
    }
}
