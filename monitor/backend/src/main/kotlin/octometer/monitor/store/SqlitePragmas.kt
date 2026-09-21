package octometer.monitor.store

import java.sql.Connection
import java.sql.Statement

private const val JOURNAL_MODE_PRAGMA = "PRAGMA journal_mode=WAL"

/**
 * The pragmas of step 4 (D3). Each connection, the writer and the reader,
 * gets the same list, before the migrations run.
 */
object SqlitePragmas {

    val statements: List<String> = listOf(
        JOURNAL_MODE_PRAGMA,
        "PRAGMA synchronous=NORMAL",
        "PRAGMA busy_timeout=5000",
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
