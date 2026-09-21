package octometer.monitor.security

import octometer.monitor.config.InvalidConfigException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Step 6 of issue #5: the server refuses a non-loopback bind address with a
 * clear error. D2 names no bind-address config key, thus this test calls
 * the check function directly, on the value that Application.kt uses.
 */
class BindAddressTest {

    @Test
    fun `a loopback address passes`() {
        assertEquals("127.0.0.1", requireLoopbackBindAddress("127.0.0.1"))
        assertEquals("localhost", requireLoopbackBindAddress("localhost"))
    }

    @Test
    fun `a non-loopback address gives a clear error`() {
        val error = assertFailsWith<InvalidConfigException> { requireLoopbackBindAddress("0.0.0.0") }
        assertEquals(
            "The bind address is '0.0.0.0'. D12 allows only a loopback address (127.0.0.1 or localhost).",
            error.message,
        )
    }
}
