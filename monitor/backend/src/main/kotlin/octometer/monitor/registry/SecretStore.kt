package octometer.monitor.registry

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val SECRETS_FILE_NAME = "apps.json"

/**
 * The secret store of D34: `secrets/apps.json`, next to `dataDir`, with
 * the app id as the key. No method of this class puts a connection
 * string into an exception message or into a log line.
 *
 * Each write goes to a temporary file in the same folder, then an atomic
 * move, so a reader never sees a half-written file.
 */
class SecretStore(dataDir: String) {

    private val secretsDir = File(File(dataDir).absoluteFile.parentFile ?: File("."), "secrets")
    private val secretsFile = File(secretsDir, SECRETS_FILE_NAME)
    private val mutex = Mutex()

    /** Writes, or replaces, the connection string of one app id. */
    suspend fun put(appId: Long, connectionString: String) {
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

    private fun readAll(): Map<String, String> {
        if (!secretsFile.isFile) return emptyMap()
        val text = secretsFile.readText(Charsets.UTF_8)
        if (text.isBlank()) return emptyMap()
        return try {
            Json.parseToJsonElement(text).jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content }
        } catch (malformed: Exception) {
            // The message never repeats the file text: it holds each
            // connection string of the registry.
            error("The secret file is not valid JSON.")
        }
    }

    private fun writeAll(entries: Map<String, String>) {
        check(secretsDir.mkdirs() || secretsDir.isDirectory) {
            "The secrets folder is not available."
        }
        val json = JsonObject(entries.mapValues { (_, value) -> JsonPrimitive(value) })
        val tempFile = File.createTempFile("apps-", ".json.tmp", secretsDir)
        try {
            tempFile.writeText(json.toString(), Charsets.UTF_8)
            Files.move(
                tempFile.toPath(),
                secretsFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            tempFile.delete()
        }
    }
}
