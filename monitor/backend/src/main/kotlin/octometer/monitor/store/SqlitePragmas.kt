package octometer.monitor.store

import java.sql.Connection
import java.sql.Statement

private const val JOURNAL_MODE_PRAGMA = "PRAGMA journal_mode=WAL"

/**
 * The busy timeout of each connection, in milliseconds (D3, step 4). The
 * user erasure route sets a value of 0 for its own checkpoint block, and
 * restores this value after (MAJOR A, second SQL review of #61).
 */
const val DEFAULT_BUSY_TIMEOUT_MILLIS = 5000

/**
 * The pragmas of step 4 (D3). Each connection, the writer and the reader,
 * gets the same list, before the migrations run.
 */
object SqlitePragmas {

    // Correction round 1 of issue #59, decision 7: the retention purge sets
    // busy_timeout to 0 around one checkpoint call, then restores this
    // value. The two places share one constant, so they never drift apart.
    const val DEFAULT_BUSY_TIMEOUT_MILLIS: Int = 5000

    val statements: List<String> = listOf(
        JOURNAL_MODE_PRAGMA,
        "PRAGMA synchronous=NORMAL",
        "PRAGMA busy_timeout=$DEFAULT_BUSY_TIMEOUT_MILLIS",
        "PRAGMA foreign_keys=ON",
        "PRAGMA secure_delete=ON",
        "PRAGMA temp_store=MEMORY",
        "PRAGMA cache_size=-65536",
    )

    fun applyTo(connection: Connection) {
        connection.createStatement().use { statement ->
            for (pragma in statements) {
                if (pragma == JOURNAL_MODE_PRAGMA) {
                    applyJournalMode(statement)
                } else {
                    statement.execute(pragma)
                }
            }
        }
    }

    // MINOR 6 of correction round 1 (SQLite and data engineer): journal_mode
    // is the one pragma that can stay at its old value with no error, for
    // example on a network share. Read the answer, and stop on a bad value.
    private fun applyJournalMode(statement: Statement) {
        statement.executeQuery(JOURNAL_MODE_PRAGMA).use { result ->
            check(result.next()) { "PRAGMA journal_mode gave no row." }
            val mode = result.getString(1)
            check(mode.equals("wal", ignoreCase = true)) {
                "PRAGMA journal_mode gave '$mode'. The store needs wal mode."
            }
        }
    }
}
