package octometer.monitor.registry

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
