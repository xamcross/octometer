package octometer.monitor.store

import java.io.File
import java.sql.Connection
import java.time.Clock
import octometer.monitor.backup.DatabaseBackup
import octometer.monitor.backup.preMigrateBackupFileName
import org.slf4j.LoggerFactory

private data class Migration(val version: Int, val resource: String)

// The migration list of step 3. Add one entry for each new numbered file
// under db/. Never edit a file that a past release already ran.
private val MIGRATIONS = listOf(
    Migration(1, "/db/001_init.sql"),
    Migration(2, "/db/002_first_page.sql"),
)

private val log = LoggerFactory.getLogger("octometer.monitor.store.MigrationRunner")

/**
 * The migration runner of step 3 (D3). It reads `PRAGMA user_version`. It
 * runs only the versions above that number. A second start of the same
 * file thus applies no migration again.
 *
 * Step 4 of issue #55 (D36): when a migration is pending, the runner
 * writes one backup file before the first migration statement. A failed
 * backup stops the migration and the start; the runner never changes a
 * database that has no fresh backup.
 */
object MigrationRunner {

    /** Runs each pending migration, in order, and returns the applied versions. */
    fun run(connection: Connection, backupsDir: File, clock: Clock = Clock.systemDefaultZone()): List<Int> {
        val current = userVersion(connection)
        val latest = MIGRATIONS.maxOf { it.version }
        // MAJOR 2 of correction round 1 (SQLite and data engineer): stop a
        // start on a database that a newer build already migrated.
        check(current <= latest) {
            "The database is at user_version $current, and this build knows $latest. " +
                "Install a newer monitor build, or restore a backup."
        }
        if (current < latest) {
            backupBeforeMigration(connection, backupsDir, current, clock)
        }
        val applied = mutableListOf<Int>()
        for (migration in MIGRATIONS) {
            if (migration.version > current) {
                applyMigration(connection, migration)
                applied += migration.version
            }
        }
        return applied
    }

    private fun backupBeforeMigration(connection: Connection, backupsDir: File, fromVersion: Int, clock: Clock) {
        val fileName = preMigrateBackupFileName(fromVersion, clock)
        try {
            val file = DatabaseBackup.writeTo(connection, backupsDir, fileName)
            log.info("The backup before the migration wrote {}.", file.name)
        } catch (failure: Exception) {
            log.error("The backup before the migration failed. The start stops. {}", failure.javaClass.simpleName)
            throw failure
        }
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

    // MINOR 1 of correction round 1 (both reviewers): call next() first, and
    // fail when a query gives no row, instead of a silent 0.
    private fun userVersion(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { result ->
                check(result.next()) { "PRAGMA user_version gave no row." }
                result.getInt(1)
            }
        }

    private fun readResource(resource: String): String {
        val stream = MigrationRunner::class.java.getResourceAsStream(resource)
            ?: error("The migration resource '$resource' is missing.")
        return stream.use { it.reader(Charsets.UTF_8).readText() }
    }
}
