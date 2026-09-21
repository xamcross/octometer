package octometer.monitor.security

import octometer.monitor.config.InvalidConfigException

// D12 names one bind address, 127.0.0.1. The name "localhost" stays out of
// this set: on Windows, a bind on that name can resolve to the IPv6
// loopback address first, and the Host check of RequestGuard.kt refuses
// the form [::1]:<port>. A bind on the name could then break the UI.
private const val LOOPBACK_ADDRESS = "127.0.0.1"

/**
 * Step 6 of issue #5: refuse a non-loopback bind address with a clear
 * error. The caller of a non-loopback value exits the process with code 2,
 * the same exit code as an invalid config value.
 *
 * @throws InvalidConfigException the address is not 127.0.0.1.
 */
fun requireLoopbackBindAddress(host: String): String {
    if (host != LOOPBACK_ADDRESS) {
        throw InvalidConfigException(
            "The bind address is '$host'. D12 allows only the loopback address $LOOPBACK_ADDRESS.",
        )
    }
    return host
}
