package octometer.monitor.store

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import octometer.monitor.backup.backupsDir
import org.sqlite.SQLiteConfig

private const val DATABASE_FILE_NAME = "octometer.db"

/**
 * The SQLite store of D3. The writer connection is private. Each write
 * runs on the one writer thread, through `write`. The reader connection
 * is private and read-only. Each read runs through `read`. A mutex admits
 * one call at a time, because a JDBC connection is not safe for two
 * parallel calls. `close()` ends both connections and the dispatcher.
 */
class SqliteDatabase private constructor(
    private val writer: Connection,
    private val reader: Connection,
    private val writerDispatcher: ExecutorCoroutineDispatcher,
) : AutoCloseable {

    private val readMutex = Mutex()

    /** Runs one block on the writer thread, with the writer connection (D3, step 5). */
    suspend fun <T> write(block: (Connection) -> T): T =
        withContext(writerDispatcher) { block(writer) }

    /** Runs one block with the reader connection. The mutex serialises each call. */
    suspend fun <T> read(block: (Connection) -> T): T =
        withContext(Dispatchers.IO) {
            readMutex.withLock { block(reader) }
        }

    override fun close() {
        runCatching { writer.close() }
        runCatching { reader.close() }
        writerDispatcher.close()
    }

    companion object {

        /**
         * Opens `octometer.db` in `dataDir`. It applies the pragmas of
         * step 4 to each connection. It runs each pending migration on the
         * writer thread. A failure closes each part that it already opened.
         *
         * [clock] names the time of a pre-migration backup file of issue
         * #55. Production code uses the default, the system clock; a test
         * gives a fixed clock.
         */
        fun open(dataDir: String, clock: Clock = Clock.systemDefaultZone()): SqliteDatabase {
            val folder = File(dataDir)
            check(folder.mkdirs() || folder.isDirectory) {
                "The data folder '$dataDir' is not available."
            }
            val url = "jdbc:sqlite:" + File(folder, DATABASE_FILE_NAME).absolutePath

            val writer = openConnection(url, readOnly = false)
            try {
                val reader = openConnection(url, readOnly = true)
                try {
                    val writerDispatcher = newWriterDispatcher()
                    try {
                        // MAJOR 2 (Kotlin backend engineer): the migration
                        // runs on the writer thread, not on the caller.
                        runBlocking(writerDispatcher) { MigrationRunner.run(writer, backupsDir(dataDir), clock) }
                    } catch (error: Throwable) {
                        writerDispatcher.close()
                        throw error
                    }
                    return SqliteDatabase(writer, reader, writerDispatcher)
                } catch (error: Throwable) {
                    // MAJOR 3 (Kotlin backend engineer): close each opened
                    // part on a throw, so a Windows handle never leaks.
                    runCatching { reader.close() }
                    throw error
                }
            } catch (error: Throwable) {
                runCatching { writer.close() }
                throw error
            }
        }

        private fun newWriterDispatcher(): ExecutorCoroutineDispatcher =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "octometer-sqlite-writer")
            }.asCoroutineDispatcher()

        // MAJOR 1 (SQLite and data engineer): the reader connection opens
        // read-only, so a write off the writer thread fails at the driver.
        private fun openConnection(url: String, readOnly: Boolean): Connection {
            val config = SQLiteConfig()
            config.setReadOnly(readOnly)
            val connection = DriverManager.getConnection(url, config.toProperties())
            SqlitePragmas.applyTo(connection)
            return connection
        }
    }
}
