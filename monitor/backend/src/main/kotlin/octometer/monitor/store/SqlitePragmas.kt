package octometer.monitor.store

import java.sql.Connection

/**
 * The pragmas of step 4 (D3). Each connection, the writer and the reader,
 * gets the same list, before the migrations run.
 */
object SqlitePragmas {

    val statements: List<String> = listOf(
        "PRAGMA journal_mode=WAL",
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
                statement.execute(pragma)
            }
        }
    }
}
