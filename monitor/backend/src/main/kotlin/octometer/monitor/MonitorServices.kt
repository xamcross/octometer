package octometer.monitor

import kotlinx.coroutines.runBlocking
import octometer.monitor.config.MonitorConfig
import octometer.monitor.registry.AppRegistryService
import octometer.monitor.registry.SecretStore
import octometer.monitor.store.SqliteDatabase
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("octometer.monitor.MonitorServices")

/**
 * The one holder of the store and the secret store for the life of the
 * application (issue #15, step 6, MAJOR 4 of the Ktor review). `module()`
 * opens it one time and closes it at `ApplicationStopped`. A later route
 * reaches the store and the registry through [apiRoutes], never through a
 * new open call. `Application.kt` needs no change when a later issue
 * (#18, #16, #17, #50, #51) adds a route.
 */
class MonitorServices private constructor(
    val config: MonitorConfig,
    val database: SqliteDatabase,
    val secretStore: SecretStore,
) : AutoCloseable {

    val appRegistryService: AppRegistryService = AppRegistryService(database, secretStore)

    override fun close() {
        database.close()
    }

    companion object {

        /**
         * Opens the store and the secret store, then runs the orphan
         * sweep of MAJOR 4 (security review). A secret can stay on the
         * disk with no app row, after a delete that stopped between its
         * two steps. The sweep removes each such secret.
         *
         * The sweep writes one log line with the removed count. It never
         * writes an app id, and it never writes a connection string.
         *
         * MAJOR 5 (second Ktor review) and MAJOR 2 (second security
         * review): a broken or a locked secrets file is a hygiene
         * problem, not a start defect. The sweep then skips, with one
         * warn-level log line, and the start goes on. Each other failure
         * (for example a database defect) closes the store again and
         * throws, so a failed start never leaves an open store or a
         * locked file.
         */
        fun open(config: MonitorConfig): MonitorServices {
            val database = SqliteDatabase.open(config.dataDir)
            try {
                val secretStore = SecretStore(config.dataDir)
                val removedOrphans = runCatching { runBlocking { sweepOrphanSecrets(database, secretStore) } }
                    .onFailure { failure ->
                        log.warn("The orphan secret sweep did not run. {}", failure.javaClass.simpleName)
                    }
                    .getOrDefault(0)
                log.info("The start removed {} orphan secret(s).", removedOrphans)
                return MonitorServices(config, database, secretStore)
            } catch (startFailure: Throwable) {
                database.close()
                throw startFailure
            }
        }
    }
}

private suspend fun sweepOrphanSecrets(database: SqliteDatabase, secretStore: SecretStore): Int {
    val appIds = database.read { reader ->
        reader.createStatement().use { statement ->
            statement.executeQuery("SELECT id FROM app").use { result ->
                val ids = mutableSetOf<Long>()
                while (result.next()) {
                    ids += result.getLong(1)
                }
                ids
            }
        }
    }
    return secretStore.removeOrphans(appIds)
}
