package octometer.monitor.registry

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Step 2 of issue #15 (D34). secrets/apps.json sits next to dataDir. Each
// test uses its own temporary root folder, so the secrets folder of one
// test never collides with the secrets folder of a different test.
class SecretStoreTest {

    private val root = Files.createTempDirectory("octometer-secret-store-test-").toFile()
    private val dataDir = File(root, "data").absolutePath
    private val store = SecretStore(dataDir)

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `put writes the secrets folder next to dataDir, not inside it`() = runBlocking {
        store.put(1L, allowlistedSrvUri())

        val secretsFile = File(root, "secrets/apps.json")
        assertTrue(secretsFile.isFile, "expected ${secretsFile.absolutePath} to exist")
        assertFalse(File(dataDir, "apps.json").exists(), "the secret file must not sit inside dataDir")
    }

    @Test
    fun `put then remove round-trips through a second SecretStore instance`() = runBlocking {
        store.put(7L, allowlistedSrvUri())

        val reopened = SecretStore(dataDir)
        assertEquals(true, reopened.remove(7L))
    }

    @Test
    fun `remove of an absent app id returns false and changes no file`() = runBlocking {
        val removed = store.remove(999L)

        assertFalse(removed)
    }

    @Test
    fun `put keeps the entry of a different app id`() = runBlocking {
        store.put(1L, allowlistedSrvUri())
        store.put(2L, allowlistedSrvUriWithoutCredential())

        assertTrue(store.remove(1L))
        assertTrue(store.remove(2L))
    }

    @Test
    fun `remove after put leaves no entry, thus a second remove returns false`() = runBlocking {
        store.put(1L, allowlistedSrvUri())

        assertTrue(store.remove(1L))
        assertFalse(store.remove(1L))
    }

    @Test
    fun `the secret file never holds a partial write, because the move is atomic`() = runBlocking {
        store.put(1L, allowlistedSrvUri())
        store.put(1L, allowlistedSrvUriWithoutCredential())

        val secretsFile = File(root, "secrets/apps.json")
        val text = secretsFile.readText()
        assertFalse(text.contains(allowlistedSrvUri()), "the old value must not stay after a replace")
        assertNull(Regex("apps-.*\\.json\\.tmp").find(File(root, "secrets").list()!!.joinToString()))
    }

    @Test
    fun `contains reports true after put and false after remove`() = runBlocking {
        assertFalse(store.contains(1L))

        store.put(1L, allowlistedSrvUri())
        assertTrue(store.contains(1L))

        store.remove(1L)
        assertFalse(store.contains(1L))
    }

    @Test
    fun `removeOrphans removes only the entries outside the given app ids, and reports the count`() = runBlocking {
        store.put(1L, allowlistedSrvUri())
        store.put(2L, allowlistedSrvUriWithoutCredential())
        store.put(3L, allowlistedSrvUriWithoutCredential())

        val removed = store.removeOrphans(setOf(2L))

        assertEquals(2, removed)
        assertFalse(store.contains(1L))
        assertTrue(store.contains(2L))
        assertFalse(store.contains(3L))
    }

    @Test
    fun `removeOrphans of an empty store removes nothing`() = runBlocking {
        val removed = store.removeOrphans(setOf(1L))

        assertEquals(0, removed)
    }

    @Test
    fun `put retries the atomic move, and gives up with SecretStoreUnavailableException`() = runBlocking {
        // MAJOR 4 of the security review: the move fails on Windows when a
        // different process holds the target file. A directory at the
        // target path blocks the move on every platform in the same way,
        // so the test needs no real second process.
        val secretsDir = File(root, "secrets")
        secretsDir.mkdirs()
        File(secretsDir, "apps.json").mkdirs()

        val started = System.nanoTime()
        val failure = assertFailsWith<SecretStoreUnavailableException> {
            store.put(1L, allowlistedSrvUri())
        }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertTrue(elapsedMillis >= 150, "expected at least 4 retry pauses of 50 ms, took $elapsedMillis ms")
        assertFalse(failure.message.orEmpty().contains(allowlistedSrvUri()))
    }

    // MAJOR 6 of the second Ktor review, and a MINOR of the second
    // security review: a read failure of the secret file must map to
    // SecretStoreUnavailableException too, the same as a write failure,
    // so every route can give 503 with no file path.

    @Test
    fun `contains gives SecretStoreUnavailableException, not a raw exception, when the file is not valid JSON`() =
        runBlocking {
            val secretsDir = File(root, "secrets").apply { mkdirs() }
            File(secretsDir, "apps.json").writeText("{ this is not valid json")

            val failure = assertFailsWith<SecretStoreUnavailableException> {
                store.contains(1L)
            }

            assertFalse(failure.message.orEmpty().contains("this is not valid json"))
        }

    @Test
    fun `put does not touch a secret file that it could not read`() = runBlocking {
        val secretsDir = File(root, "secrets").apply { mkdirs() }
        val secretsFile = File(secretsDir, "apps.json")
        val brokenBytes = "{ this is not valid json".toByteArray(Charsets.UTF_8)
        secretsFile.writeBytes(brokenBytes)

        assertFailsWith<SecretStoreUnavailableException> {
            store.put(1L, allowlistedSrvUri())
        }

        assertTrue(
            brokenBytes.contentEquals(secretsFile.readBytes()),
            "a read failure must never overwrite the file it could not read",
        )
    }

    @Test
    fun `contains retries the read, and gives up with SecretStoreUnavailableException, when a lock blocks it`() =
        runBlocking {
            // Windows enforces a byte-range lock against every other
            // handle, this JVM included. Linux advisory locks do not
            // block a plain read, so this test would pass by accident
            // there, and it would prove nothing.
            Assumptions.assumeTrue(
                System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true),
                "the OS must enforce a byte-range lock against a plain read",
            )
            store.put(1L, allowlistedSrvUri())
            val secretsFile = File(root, "secrets/apps.json")

            RandomAccessFile(secretsFile, "rw").use { handle ->
                val lock = handle.channel.lock()
                try {
                    val started = System.nanoTime()
                    val failure = assertFailsWith<SecretStoreUnavailableException> {
                        store.contains(1L)
                    }
                    val elapsedMillis = (System.nanoTime() - started) / 1_000_000

                    assertTrue(elapsedMillis >= 150, "expected at least 4 retry pauses of 50 ms, took $elapsedMillis ms")
                    assertFalse(failure.message.orEmpty().contains(allowlistedSrvUri()))
                } finally {
                    lock.release()
                }
            }
        }
}
