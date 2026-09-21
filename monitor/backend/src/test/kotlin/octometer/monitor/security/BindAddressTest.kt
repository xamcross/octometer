package octometer.monitor.security

import octometer.monitor.config.InvalidConfigException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Step 6 of issue #5: the server refuses a non-loopback bind address with a
 * clear error. D2 names no bind-address config key, thus this test calls
 * the check function directly, on the value that Application.kt uses.
 *
 * D12 names one bind address, 127.0.0.1. The set holds only that address:
 * on Windows, a bind on the name "localhost" can resolve to the IPv6
 * loopback address first, and the Host check refuses that form.
 */
class BindAddressTest {

    @Test
    fun `the loopback address of D12 passes`() {
        assertEquals("127.0.0.1", requireLoopbackBindAddress("127.0.0.1"))
    }

    @Test
    fun `the name localhost is not the address of D12, thus it gives a clear error`() {
        val error = assertFailsWith<InvalidConfigException> { requireLoopbackBindAddress("localhost") }
        assertEquals(
            "The bind address is 'localhost'. D12 allows only the loopback address 127.0.0.1.",
            error.message,
        )
    }

    @Test
    fun `a non-loopback address gives a clear error`() {
        val error = assertFailsWith<InvalidConfigException> { requireLoopbackBindAddress("0.0.0.0") }
        assertEquals(
            "The bind address is '0.0.0.0'. D12 allows only the loopback address 127.0.0.1.",
            error.message,
        )
    }
}
