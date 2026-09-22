package octometer.demo

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests of the demo app settings (security review MAJOR 3). The bind
 * address is the one security setting of this app, so a test must hold
 * it fixed.
 */
class DemoSettingsTest {

    @Test
    fun `the app binds to the loopback address only`() {
        assertEquals("127.0.0.1", DemoSettings.fromEnvironment().host)
    }
}
