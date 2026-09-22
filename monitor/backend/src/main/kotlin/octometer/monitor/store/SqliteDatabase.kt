package octometer.monitor.store

import java.io.File
import java.io.RandomAccessFile
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import octometer.monitor.backup.backupsDir
import org.slf4j.LoggerFactory
import org.sqlite.SQLiteConfig

private const val DATABASE_FILE_NAME = "octometer.db"
private const val WRITER_SHUTDOWN_TIMEOUT_SECONDS = 5L

// The two header bytes of a SQLite file at offset 18 and 19: the write
// version and the read version. The value 1 names the legacy rollback
// journal; the value 2 names WAL. Source: the SQLite file format spec.
private const val HEADER_VERSION_OFFSET = 18
private const val HEADER_VERSION_LENGTH = 2
private const val ROLLBACK_JOURNAL_VERSION: Byte = 1

private val log = LoggerFactory.getLogger("octometer.monitor.store.SqliteDatabase")

/**
 * BLOCKER 1 of correction round 1 for issue #55 (the reliability review).
 * A person who copies a backup file over `octometer.db` by hand, but
 * keeps the old `octometer.db-wal` file, gets the old data back with no
 * warning: `PRAGMA journal_mode=WAL` replays the stale log over the
 * restored file. [SqliteDatabase.open] throws this instead of opening.
 */
class RestoredDatabaseNeedsCleanupException(message: String) : Exception(message)

// Issue #55: a plain ExecutorCoroutineDispatcher.close() only asks the
// executor to shut down; it does not wait for the thread to end. This
// waits, so the writer thread never outlives close(), on a success or
// on a failed open().
//
// SQLite MINOR 5 of the SQLite and file system review: a timeout must not
// stay silent. The caller learns nothing else, because a write of the
// stalled task can still finish and lock a file after this returns.
private fun awaitWriterShutdown(executor: ExecutorService) {
    val terminated = runCatching {
        executor.awaitTermination(WRITER_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }.getOrDefault(false)
    if (!terminated) {
        log.warn("The writer thread did not end within {} seconds.", WRITER_SHUTDOWN_TIMEOUT_SECONDS)
    }
}

// BLOCKER 1 of correction round 1: refuse a start on a restored database
// with a stale write-ahead log beside it. The code never deletes the two
// side files itself; only a person, following the README, does that.
private fun refuseIfRestoredWithStaleWal(folder: File) {
    val databaseFile = File(folder, DATABASE_FILE_NAME)
    if (!databaseFile.isFile || databaseFile.length() < HEADER_VERSION_OFFSET + HEADER_VERSION_LENGTH) return
    val walFile = File(folder, "$DATABASE_FILE_NAME-wal")
    if (!walFile.isFile || walFile.length() <= 0L) return

    val versionBytes = ByteArray(HEADER_VERSION_LENGTH)
    RandomAccessFile(databaseFile, "r").use { file ->
        file.seek(HEADER_VERSION_OFFSET.toLong())
        file.readFully(versionBytes)
    }
    val isRollbackJournalFile = versionBytes[0] == ROLLBACK_JOURNAL_VERSION && versionBytes[1] == ROLLBACK_JOURNAL_VERSION
    if (isRollbackJournalFile) {
        log.error(
            "A stale write-ahead log file lies beside a restored database. " +
                "Delete octometer.db-wal and octometer.db-shm, then start again.",
        )
        throw RestoredDatabaseNeedsCleanupException(
            "A stale write-ahead log file lies beside a restored database. " +
                "Delete octometer.db-wal and octometer.db-shm, then start again.",
        )
    }
}

/**
 * The SQLite store of D3. The writer connection is private. Each write
 * runs on the one writer thread, through `write`. The reader connection
 * is private and read-only. Each read runs through `read`, inside one
 * read transaction, so a caller with more than one statement reads one
 * snapshot. A mutex admits one call at a time, because a JDBC connection
 * is not safe for two parallel calls. `close()` ends both connections and
 * the dispatcher.
 */
class SqliteDatabase private constructor(
    private val writer: Connection,
    private val reader: Connection,
    private val writerExecutor: ExecutorService,
    private val writerDispatcher: ExecutorCoroutineDispatcher,
) : AutoCloseable {

    private val readMutex = Mutex()

    /** Runs one block on the writer thread, with the writer connection (D3, step 5). */
    suspend fun <T> write(block: (Connection) -> T): T =
        withContext(writerDispatcher) { block(writer) }

    /**
     * Runs one block with the reader connection, inside one read
     * transaction. `BEGIN` opens the transaction before the block runs,
     * and `COMMIT` closes it after the block returns, so each statement
     * of the block reads the one snapshot of the `BEGIN` (MAJOR 1 of
     * correction round 1 for pull request #127). Without this rule the
     * reader connection stays in autocommit mode, and each statement of
     * the block opens and closes its own snapshot; a write of the poll
     * loop between two statements then gives an impossible row.
     *
     * A block that throws gets `ROLLBACK`, and the throw still reaches
     * the caller; a coroutine cancellation still rolls back and still
     * propagates. The mutex serialises each call.
     */
    suspend fun <T> read(block: (Connection) -> T): T =
        withContext(Dispatchers.IO) {
            readMutex.withLock {
                reader.createStatement().use { it.execute("BEGIN") }
                var committed = false
                var failure: Throwable? = null
                try {
                    val result = block(reader)
                    reader.createStatement().use { it.execute("COMMIT") }
                    committed = true
                    result
                } catch (error: Throwable) {
                    failure = error
                    throw error
                } finally {
                    if (!committed) {
                        rollbackReader(failure)
                    }
                }
            }
        }

    // The rollback runs in a finally, and a failed ROLLBACK never stays
    // silent; it joins the original throw as a suppressed exception. The
    // same pattern protects the writer connection in EventStore.
    private fun rollbackReader(failure: Throwable?) {
        try {
            reader.createStatement().use { it.execute("ROLLBACK") }
        } catch (rollbackError: Throwable) {
            failure?.addSuppressed(rollbackError)
        }
    }

    // SQLite MINOR 4 of correction round 1: the dispatcher closes first,
    // so it accepts no new write task. The wait for the writer thread
    // then follows, before the two connections close. A write in flight
    // used to reach a closed connection; now it always finishes first.
    override fun close() {
        writerDispatcher.close()
        awaitWriterShutdown(writerExecutor)
        runCatching { writer.close() }
        runCatching { reader.close() }
    }

    companion object {

        /**
         * Opens `octometer.db` in `dataDir`. It applies the pragmas of
         * step 4 to each connection. It runs each pending migration on the
         * writer thread. A failure closes each part that it already opened.
         *
         * [backupDir] names the folder of a pre-migration backup file of
         * issue #55. It defaults to the sibling folder `backups` of
         * [dataDir]; [octometer.monitor.MonitorServices] gives the
         * resolved value of the config key `backupDir`.
         *
         * [clock] names the time of that file. Production code uses the
         * default, the system clock; a test gives a fixed clock.
         *
         * BLOCKER 1 of correction round 1: this refuses the start when
         * `octometer.db` is a restored rollback-journal file and a
         * write-ahead log file with content still lies beside it.
         */
        fun open(
            dataDir: String,
            backupDir: String = backupsDir(dataDir).absolutePath,
            clock: Clock = Clock.systemDefaultZone(),
        ): SqliteDatabase {
            val folder = File(dataDir)
            check(folder.mkdirs() || folder.isDirectory) {
                "The data folder '$dataDir' is not available."
            }
            refuseIfRestoredWithStaleWal(folder)
            val url = "jdbc:sqlite:" + File(folder, DATABASE_FILE_NAME).absolutePath

            val writer = openConnection(url, readOnly = false)
            try {
                val reader = openConnection(url, readOnly = true)
                try {
                    val writerExecutor = newWriterExecutor()
                    val writerDispatcher = writerExecutor.asCoroutineDispatcher()
                    try {
                        // MAJOR 2 (Kotlin backend engineer): the migration
                        // runs on the writer thread, not on the caller.
                        runBlocking(writerDispatcher) { MigrationRunner.run(writer, File(backupDir), clock) }
                    } catch (error: Throwable) {
                        writerDispatcher.close()
                        awaitWriterShutdown(writerExecutor)
                        throw error
                    }
                    return SqliteDatabase(writer, reader, writerExecutor, writerDispatcher)
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

        private fun newWriterExecutor(): ExecutorService =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "octometer-sqlite-writer")
            }

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
