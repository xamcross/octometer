package octometer.monitor.registry

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("octometer.monitor.registry.SecretStore")

private const val SECRETS_FILE_NAME = "apps.json"
private const val MAX_MOVE_ATTEMPTS = 5
private const val MOVE_RETRY_PAUSE_MILLIS = 50L
private const val LEFTOVER_TEMP_FILE_PREFIX = "apps-"
private const val LEFTOVER_TEMP_FILE_SUFFIX = ".json.tmp"

/**
 * The secrets folder is not available after [MAX_MOVE_ATTEMPTS] tries of
 * the atomic move (MAJOR 4 of the security review). The caller answers
 * 503. The message never holds a connection string.
 */
class SecretStoreUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The secret store of D34: `secrets/apps.json`, next to `dataDir`, with
 * the app id as the key. No method of this class puts a connection
 * string into an exception message or into a log line.
 *
 * Each write goes to a temporary file in the same folder, then an atomic
 * move, so a reader never sees a half-written file. The `Mutex` here
 * guards one process only; issue #58 adds a lock file for two monitor
 * processes on the same folder.
 */
open class SecretStore(dataDir: String) {

    private val secretsDir = File(File(dataDir).absoluteFile.parentFile ?: File("."), "secrets")
    private val secretsFile = File(secretsDir, SECRETS_FILE_NAME)
    private val mutex = Mutex()

    /**
     * Writes, or replaces, the connection string of one app id.
     *
     * `open`, so a test of the second security review can subclass this
     * class, and throw a chosen exception from one call. Production code
     * never subclasses it.
     */
    open suspend fun put(appId: Long, connectionString: String) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                writeAll(readAll() + (appId.toString() to connectionString))
            }
        }
    }

    /** Removes the entry of one app id. Returns whether an entry was there. */
    suspend fun remove(appId: Long): Boolean =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val key = appId.toString()
                val current = readAll()
                if (current.containsKey(key)) {
                    writeAll(current - key)
                    true
                } else {
                    false
                }
            }
        }

    /** Reports whether an entry of one app id is in the store, with no removal. */
    suspend fun contains(appId: Long): Boolean =
        mutex.withLock {
            withContext(Dispatchers.IO) { readAll().containsKey(appId.toString()) }
        }

    /**
     * Removes each entry whose app id is not in [existingAppIds] (the
     * sweep of MAJOR 4 of the security review, at the application
     * start). Returns the count of removed entries; the caller writes
     * that count to the log, and never the removed app ids.
     */
    suspend fun removeOrphans(existingAppIds: Set<Long>): Int =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val current = readAll()
                val orphanKeys = current.keys.filter { key -> key.toLongOrNull() !in existingAppIds }
                if (orphanKeys.isEmpty()) return@withContext 0
                writeAll(current - orphanKeys.toSet())
                orphanKeys.size
            }
        }

    /**
     * Removes each leftover `apps-*.json.tmp` file of the secrets folder
     * (issue #141). A kill of the process between the temporary write and
     * the atomic move of [writeAll] can leave such a file behind. The
     * start sweep of `MonitorServices.open` calls this once, before the
     * first write.
     *
     * Returns the removed count. A failed delete gives one warning with
     * the count only, never a file name or a file path, and it does not
     * stop the caller.
     */
    suspend fun removeLeftoverTempFiles(): Int =
        mutex.withLock {
            withContext(Dispatchers.IO) { deleteLeftoverTempFiles() }
        }

    private fun deleteLeftoverTempFiles(): Int {
        val leftoverFiles = secretsDir.listFiles { file ->
            file.name.startsWith(LEFTOVER_TEMP_FILE_PREFIX) && file.name.endsWith(LEFTOVER_TEMP_FILE_SUFFIX)
        } ?: return 0
        var removedCount = 0
        var failedCount = 0
        for (leftoverFile in leftoverFiles) {
            if (leftoverFile.delete()) {
                removedCount += 1
            } else {
                failedCount += 1
            }
        }
        if (failedCount > 0) {
            log.warn("The start could not remove {} leftover temporary secret file(s).", failedCount)
        }
        return removedCount
    }

    // MAJOR 6 (second Ktor review) and MINOR (second security review): the
    // old code read the file with no retry and with no
    // SecretStoreUnavailableException. A locked apps.json (a backup tool,
    // an antivirus scan) then threw a raw IOException, and the caller
    // answered 500, not the 503 of the decision. A read failure now maps
    // to the same exception as a failed write. The message holds no file
    // path and no file text. This code never writes a new file over one
    // it could not read.
    private fun readAll(): Map<String, String> {
        if (!secretsFile.isFile) return emptyMap()
        val text = readTextWithRetry()
        if (text.isBlank()) return emptyMap()
        return try {
            Json.parseToJsonElement(text).jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content }
        } catch (malformed: Exception) {
            // The message never repeats the file text: it holds each
            // connection string of the registry.
            throw SecretStoreUnavailableException("The secret file is not valid JSON.")
        }
    }

    private fun readTextWithRetry(): String {
        var attempt = 1
        while (true) {
            try {
                return secretsFile.readText(Charsets.UTF_8)
            } catch (readFailure: IOException) {
                if (attempt >= MAX_MOVE_ATTEMPTS) {
                    throw SecretStoreUnavailableException(
                        "The secret file read failed after $MAX_MOVE_ATTEMPTS attempts.",
                        readFailure,
                    )
                }
                Thread.sleep(MOVE_RETRY_PAUSE_MILLIS)
                attempt += 1
            }
        }
    }

    // MINOR 2 (third security review): a plain file at the secrets folder
    // path made this throw a raw IllegalStateException, thus the caller
    // answered 500, not the 503 of the decision. This now throws the one
    // exception that every route maps to 503.
    private fun writeAll(entries: Map<String, String>) {
        if (!secretsDir.mkdirs() && !secretsDir.isDirectory) {
            throw SecretStoreUnavailableException("The secrets folder is not available.")
        }
        restrictToOwner(secretsDir)
        val json = JsonObject(entries.mapValues { (_, value) -> JsonPrimitive(value) })
        val tempFile = File.createTempFile("apps-", ".json.tmp", secretsDir)
        try {
            tempFile.writeText(json.toString(), Charsets.UTF_8)
            moveWithRetry(tempFile)
        } finally {
            tempFile.delete()
        }
    }

    // MAJOR 4 of the security review: on Windows the atomic move fails
    // when a different process holds apps.json, for example a backup
    // tool or an antivirus scan. A retry gives that process time to
    // release its handle. The temporary file stays the same file across
    // every attempt, and the finally block of writeAll removes it once,
    // whether the move succeeds or not.
    private fun moveWithRetry(tempFile: File) {
        var attempt = 1
        while (true) {
            try {
                Files.move(
                    tempFile.toPath(),
                    secretsFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                return
            } catch (moveFailure: IOException) {
                if (attempt >= MAX_MOVE_ATTEMPTS) {
                    throw SecretStoreUnavailableException(
                        "The secret file move failed after $MAX_MOVE_ATTEMPTS attempts.",
                        moveFailure,
                    )
                }
                Thread.sleep(MOVE_RETRY_PAUSE_MILLIS)
                attempt += 1
            }
        }
    }

    // MINOR 5 of the security review: an owner-only folder, because D2
    // lets the owner point dataDir (and so this sibling folder) at a
    // shared location. A filesystem with no POSIX permission support,
    // for example exFAT, keeps its default rights; the attempt never
    // fails the write.
    private fun restrictToOwner(directory: File) {
        runCatching {
            val posixView = Files.getFileAttributeView(
                directory.toPath(),
                java.nio.file.attribute.PosixFileAttributeView::class.java,
            )
            if (posixView != null) {
                Files.setPosixFilePermissions(directory.toPath(), PosixFilePermissions.fromString("rwx------"))
            }
        }
    }
}
