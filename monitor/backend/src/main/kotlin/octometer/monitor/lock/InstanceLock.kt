package octometer.monitor.lock

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption

/** The name of the lock file inside `dataDir` (design decision D3, issue #58). */
const val LOCK_FILE_NAME = "octometer.lock"

/**
 * Decision 2 of issue #58: a second monitor instance must refuse to
 * start. `main` calls [InstanceLock.acquire] right after `loadConfig`,
 * and before `MonitorServices.open` (the note of 2026-09-22: the orphan
 * secret sweep of `open` must never race a second process on the same
 * secret file).
 */
class InstanceLockFailedException(message: String) : Exception(message)

/**
 * One held lock of [LOCK_FILE_NAME] in `dataDir`, for the life of the
 * process. `main` keeps this on a field, and calls [close] from a
 * shutdown hook, so the lock releases on each exit path.
 */
class InstanceLock private constructor(
    private val channel: FileChannel,
    private val fileLock: FileLock,
) : AutoCloseable {

    override fun close() {
        runCatching { fileLock.release() }
        runCatching { channel.close() }
    }

    companion object {

        /**
         * Opens [LOCK_FILE_NAME] inside [dataDir] and takes an exclusive
         * lock on it with `FileChannel.tryLock()`. Throws
         * [InstanceLockFailedException] when a second process (or, in one
         * test JVM only, a second channel) already holds the lock. The
         * message names the file name only, never the data folder path.
         */
        fun acquire(dataDir: String): InstanceLock {
            val file = File(dataDir, LOCK_FILE_NAME)
            val channel = FileChannel.open(
                file.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
            )
            val fileLock = try {
                channel.tryLock()
            } catch (heldInThisJvm: OverlappingFileLockException) {
                runCatching { channel.close() }
                throw lockFailedException(heldInThisJvm)
            } catch (ioFailure: IOException) {
                runCatching { channel.close() }
                throw lockFailedException(ioFailure)
            }
            if (fileLock == null) {
                runCatching { channel.close() }
                throw lockFailedException(cause = null)
            }
            return InstanceLock(channel, fileLock)
        }

        private fun lockFailedException(cause: Throwable?): InstanceLockFailedException {
            val failure = InstanceLockFailedException("Another monitor instance holds $LOCK_FILE_NAME in the data folder.")
            if (cause != null) failure.initCause(cause)
            return failure
        }
    }
}
