package octometer.monitor.security

import octometer.monitor.config.InvalidConfigException

// D12: the server binds to a loopback address and refuses another one. D2
// names no bind-address config key, thus this checks the constant that
// Application.kt uses. The pull request text names this decision.
private val LOOPBACK_ADDRESSES = setOf("127.0.0.1", "localhost")

/**
 * Step 6 of issue #5: refuse a non-loopback bind address with a clear
 * error. The caller of a non-loopback value exits the process with code 2,
 * the same exit code as an invalid config value.
 *
 * @throws InvalidConfigException the address is not a loopback address.
 */
fun requireLoopbackBindAddress(host: String): String {
    if (host !in LOOPBACK_ADDRESSES) {
        throw InvalidConfigException(
            "The bind address is '$host'. D12 allows only a loopback address (127.0.0.1 or localhost).",
        )
    }
    return host
}
