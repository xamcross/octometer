package octometer.monitor.store

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

private const val DATABASE_FILE_NAME = "octometer.db"

/**
 * The SQLite store of D3: one file `octometer.db` in `dataDir`, one writer
 * connection on its own single-thread dispatcher, and one separate reader
 * connection. `close()` ends both connections and the dispatcher.
 */
class SqliteDatabase private constructor(
    val writer: Connection,
    val reader: Connection,
    private val writerDispatcher: ExecutorCoroutineDispatcher,
) : AutoCloseable {

    /** The single thread that must run each write of the store (D3, step 5). */
    val dispatcher: CoroutineDispatcher get() = writerDispatcher

    override fun close() {
        runCatching { writer.close() }
        runCatching { reader.close() }
        writerDispatcher.close()
    }

    companion object {

        /**
         * Opens `octometer.db` in `dataDir`, applies the pragmas of step 4 to
         * each connection, and runs each pending migration on the writer.
         */
        fun open(dataDir: String): SqliteDatabase {
            val folder = File(dataDir)
            folder.mkdirs()
            val url = "jdbc:sqlite:" + File(folder, DATABASE_FILE_NAME).absolutePath

            val writerDispatcher = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "octometer-sqlite-writer")
            }.asCoroutineDispatcher()

            val writer = openConnection(url)
            val reader = openConnection(url)
            MigrationRunner.run(writer)

            return SqliteDatabase(writer, reader, writerDispatcher)
        }

        private fun openConnection(url: String): Connection {
            val connection = DriverManager.getConnection(url)
            SqlitePragmas.applyTo(connection)
            return connection
        }
    }
}
