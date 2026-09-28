package octometer.monitor.security

import octometer.monitor.config.InvalidConfigException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Step 6 of issue #5, extended by D52 (issue #295, brief review B4): the
 * server refuses a bind address other than 127.0.0.1 or 0.0.0.0, with a
 * clear error. D2 names the bind config key `bind` since issue #295;
 * this test calls the check function directly, on the value that
 * Application.kt uses.
 *
 * The name "localhost" stays out of the allowed set: on Windows, a bind
 * on that name can resolve to the IPv6 loopback address first, and the
 * Host check refuses that form.
 */
class BindAddressTest {

    @Test
    fun `the loopback address of D12 passes`() {
        assertEquals("127.0.0.1", requireLoopbackBindAddress("127.0.0.1"))
    }

    @Test
    fun `the public bind address of D52 passes`() {
        assertEquals("0.0.0.0", requireLoopbackBindAddress("0.0.0.0"))
    }

    @Test
    fun `the name localhost is not an allowed address, thus it gives a clear error`() {
        val error = assertFailsWith<InvalidConfigException> { requireLoopbackBindAddress("localhost") }
        assertEquals(
            "The bind address is 'localhost'. Octometer allows only the addresses 127.0.0.1 and 0.0.0.0.",
            error.message,
        )
    }

    @Test
    fun `another address gives a clear error`() {
        val error = assertFailsWith<InvalidConfigException> { requireLoopbackBindAddress("10.0.0.5") }
        assertEquals(
            "The bind address is '10.0.0.5'. Octometer allows only the addresses 127.0.0.1 and 0.0.0.0.",
            error.message,
        )
    }
}
