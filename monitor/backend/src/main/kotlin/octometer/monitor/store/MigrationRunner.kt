package octometer.monitor.store

import java.sql.Connection

private data class Migration(val version: Int, val resource: String)

// The migration list of step 3. Add one entry for each new numbered file
// under db/. Never edit a file that a past release already ran.
private val MIGRATIONS = listOf(
    Migration(1, "/db/001_init.sql"),
)

/**
 * The migration runner of step 3 (D3). It compares each migration version
 * with `PRAGMA user_version` and runs only the versions above it, thus a
 * second start of the same file applies no migration again.
 */
object MigrationRunner {

    /** Runs each pending migration, in order, and returns the applied versions. */
    fun run(connection: Connection): List<Int> {
        val current = userVersion(connection)
        val applied = mutableListOf<Int>()
        for (migration in MIGRATIONS) {
            if (migration.version > current) {
                applyMigration(connection, migration)
                applied += migration.version
            }
        }
        return applied
    }

    private fun applyMigration(connection: Connection, migration: Migration) {
        val sql = readResource(migration.resource)
        connection.autoCommit = false
        try {
            connection.createStatement().use { it.executeUpdate(sql) }
            connection.createStatement().use { it.execute("PRAGMA user_version = ${migration.version}") }
            connection.commit()
        } catch (error: Exception) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    private fun userVersion(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { it.getInt(1) }
        }

    private fun readResource(resource: String): String {
        val stream = MigrationRunner::class.java.getResourceAsStream(resource)
            ?: error("The migration resource '$resource' is missing.")
        return stream.use { it.reader(Charsets.UTF_8).readText() }
    }
}
