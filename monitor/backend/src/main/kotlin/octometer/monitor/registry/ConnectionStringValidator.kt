package octometer.monitor.registry

// D11 and section 4.3: the accepted forms and the forbidden options. The
// map key is the lower-case option name; the map value is the forbidden
// value, matched case-insensitively.
private val FORBIDDEN_BOOLEAN_OPTIONS = mapOf(
    "tls" to "false",
    "ssl" to "false",
    "tlsinsecure" to "true",
    "tlsallowinvalidcertificates" to "true",
    "tlsallowinvalidhostnames" to "true",
)

private const val SRV_PREFIX = "mongodb+srv://"
private const val STANDARD_PREFIX = "mongodb://"

/** The result of the check of D11. It never holds the checked text. */
sealed class ConnectionStringCheck {
    object Valid : ConnectionStringCheck()

    /** [message] names the failed rule. It never repeats the checked text. */
    data class Invalid(val message: String) : ConnectionStringCheck()
}

/**
 * The URI check of D11 and section 4.3 (step 1). It runs on the raw text,
 * before a driver parses it (D10). It accepts `mongodb+srv://`, and
 * `mongodb://` only for a loopback host. It rejects each insecure TLS
 * option and `readPreference`, because the monitor sets the read
 * preference in code. Each error message names the rule, never the text.
 */
object ConnectionStringValidator {

    fun check(connectionString: String): ConnectionStringCheck {
        val prefix = when {
            connectionString.startsWith(SRV_PREFIX) -> SRV_PREFIX
            connectionString.startsWith(STANDARD_PREFIX) -> STANDARD_PREFIX
            else -> return invalid("The scheme must be mongodb+srv:// or mongodb://.")
        }

        val remainder = connectionString.substring(prefix.length)

        if (prefix == STANDARD_PREFIX && !isLoopbackOnly(hostSegmentOf(remainder))) {
            return invalid("A mongodb:// connection string needs a loopback host.")
        }

        for ((key, value) in optionsOf(remainder)) {
            val lowerKey = key.lowercase()
            if (lowerKey == "readpreference") {
                return invalid("The option readPreference is not allowed in a connection string.")
            }
            val forbiddenValue = FORBIDDEN_BOOLEAN_OPTIONS[lowerKey]
            if (forbiddenValue != null && value.equals(forbiddenValue, ignoreCase = true)) {
                return invalid("The option $key=$forbiddenValue is not allowed in a connection string.")
            }
        }

        return ConnectionStringCheck.Valid
    }

    private fun invalid(message: String) = ConnectionStringCheck.Invalid(message)

    // The host list sits between the prefix and the first "/" or "?". A
    // user name and a password, when present, sit before the last "@" of
    // that part. The connection string spec requires a percent-encoded
    // "@" and "/" inside a user name or a password, thus one plain "@" is
    // always the delimiter.
    private fun hostSegmentOf(remainder: String): String {
        val pathStart = remainder.indexOfFirst { it == '/' || it == '?' }
        val beforePath = if (pathStart == -1) remainder else remainder.substring(0, pathStart)
        val at = beforePath.lastIndexOf('@')
        return if (at == -1) beforePath else beforePath.substring(at + 1)
    }

    private fun isLoopbackOnly(hostSegment: String): Boolean {
        if (hostSegment.isBlank()) return false
        return hostSegment.split(",").all { entry -> isLoopbackHost(hostnameOf(entry)) }
    }

    private fun hostnameOf(hostAndPort: String): String {
        val trimmed = hostAndPort.trim()
        if (trimmed.startsWith("[")) {
            val end = trimmed.indexOf(']')
            return if (end == -1) trimmed else trimmed.substring(1, end)
        }
        val colon = trimmed.indexOf(':')
        return if (colon == -1) trimmed else trimmed.substring(0, colon)
    }

    private fun isLoopbackHost(host: String): Boolean =
        host.equals("localhost", ignoreCase = true) || host == "127.0.0.1" || host == "::1"

    private fun optionsOf(remainder: String): List<Pair<String, String>> {
        val queryStart = remainder.indexOf('?')
        if (queryStart == -1) return emptyList()
        val query = remainder.substring(queryStart + 1)
        return query.split("&").mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val eq = pair.indexOf('=')
            if (eq == -1) pair to "" else pair.substring(0, eq) to pair.substring(eq + 1)
        }
    }
}
