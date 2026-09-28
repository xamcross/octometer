package octometer.monitor.security

import octometer.monitor.config.InvalidConfigException

// D12 names the loopback address, 127.0.0.1. D52 (issue #295) adds the
// public bind 0.0.0.0 for a Fly deploy. The name "localhost" stays out
// of this set: on Windows, a bind on that name can resolve to the IPv6
// loopback address first, and the Host check of RequestGuard.kt refuses
// the form [::1]:<port>. A bind on the name could then break the UI.
private val ALLOWED_BIND_ADDRESSES = setOf("127.0.0.1", "0.0.0.0")

/**
 * Step 6 of issue #5, extended by D52 (issue #295): refuse a bind
 * address other than 127.0.0.1 or 0.0.0.0, with a clear error. The
 * caller of an invalid value exits the process with code 2, the same
 * exit code as an invalid config value.
 *
 * @throws InvalidConfigException the address is not 127.0.0.1 or 0.0.0.0.
 */
fun requireLoopbackBindAddress(host: String): String {
    if (host !in ALLOWED_BIND_ADDRESSES) {
        throw InvalidConfigException(
            "The bind address is '$host'. Octometer allows only the addresses 127.0.0.1 and 0.0.0.0.",
        )
    }
    return host
}
