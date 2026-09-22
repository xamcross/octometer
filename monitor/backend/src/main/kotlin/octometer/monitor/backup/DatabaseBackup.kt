package octometer.monitor.backup

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.Connection

private const val MAX_MOVE_ATTEMPTS = 5
private const val MOVE_RETRY_PAUSE_MILLIS = 50L
private const val TEMP_FILE_SUFFIX = ".tmp"

/**
 * A failed backup (step 1 of issue #55). The message never holds a full
 * path, only a file name.
 */
class BackupFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The backup method of D3 and D36: `VACUUM INTO`, safe for a database in
 * WAL mode. The code never copies `octometer.db` with a plain file copy.
 *
 * `VACUUM INTO` refuses to write over an existing file, thus this writes
 * to a temporary name first, then moves the finished file into place. A
 * reader of the backups folder never sees a half-written file under the
 * final name.
 */
object DatabaseBackup {

    /**
     * Writes one backup of the database of [connection] to
     * [finalFileName] inside [backupsDir]. Returns the final file.
     * Throws [BackupFailedException] when the write or the move fails.
     */
    fun writeTo(connection: Connection, backupsDir: File, finalFileName: String): File {
        if (!backupsDir.mkdirs() && !backupsDir.isDirectory) {
            throw BackupFailedException("The backups folder is not available.")
        }
        val finalFile = File(backupsDir, finalFileName)
        val tempFile = File(backupsDir, "$finalFileName$TEMP_FILE_SUFFIX")
        // A stale temporary file of an earlier crash blocks VACUUM INTO,
        // because that statement refuses to write over an existing file.
        tempFile.delete()
        try {
            runVacuumInto(connection, tempFile)
            moveIntoPlace(tempFile, finalFile)
        } finally {
            tempFile.delete()
        }
        return finalFile
    }

    private fun runVacuumInto(connection: Connection, tempFile: File) {
        try {
            connection.prepareStatement("VACUUM INTO ?").use { statement ->
                statement.setString(1, tempFile.absolutePath)
                statement.execute()
            }
        } catch (failure: Exception) {
            throw BackupFailedException("The database backup did not write. ${failure.javaClass.simpleName}", failure)
        }
    }

    // The pattern of SecretStore.moveWithRetry: on Windows a move fails
    // while a different process holds a short-lived handle on the target
    // file, for example a virus scan. A short retry gives that process
    // time to release its handle.
    private fun moveIntoPlace(tempFile: File, finalFile: File) {
        var attempt = 1
        while (true) {
            try {
                Files.move(
                    tempFile.toPath(),
                    finalFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                return
            } catch (moveFailure: IOException) {
                if (attempt >= MAX_MOVE_ATTEMPTS) {
                    throw BackupFailedException(
                        "The backup file did not move into place after $MAX_MOVE_ATTEMPTS attempts.",
                        moveFailure,
                    )
                }
                Thread.sleep(MOVE_RETRY_PAUSE_MILLIS)
                attempt += 1
            }
        }
    }
}
