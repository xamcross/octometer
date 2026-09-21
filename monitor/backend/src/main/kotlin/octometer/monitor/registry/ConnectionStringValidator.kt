package octometer.monitor.registry

private const val SRV_PREFIX = "mongodb+srv://"
private const val STANDARD_PREFIX = "mongodb://"

// D11 and section 4.3: the allow-list of step 1, after the correction of
// the pull request review. The monitor sets the read preference and each
// TLS option in code (D10), thus the registry needs a very small set of
// options. Each name below is lower-case, because the compare ignores the
// letter case of the option name. tls and ssl pass only with the exact
// value "true"; each other name passes with any value.
private val ALLOWED_OPTIONS_ANY_VALUE = setOf(
    "retrywrites",
    "retryreads",
    "w",
    "appname",
    "authsource",
    "replicaset",
)
private val ALLOWED_TRUE_ONLY_OPTIONS = setOf("tls", "ssl")
private const val REQUIRED_TRUE_VALUE = "true"

/** The result of the check of D11. It never holds the checked text. */
sealed class ConnectionStringCheck {
    object Valid : ConnectionStringCheck()

    /** [message] names the failed rule. It never repeats the checked text. */
    data class Invalid(val message: String) : ConnectionStringCheck()
}

/**
 * The URI check of D11 and section 4.3 (step 1). It runs on the raw text,
 * before a driver parses it (D10). It accepts `mongodb+srv://`, and
 * `mongodb://` only for a loopback host.
 *
 * The query check is an allow-list, not a deny-list. Only the names of
 * [ALLOWED_OPTIONS_ANY_VALUE] and [ALLOWED_TRUE_ONLY_OPTIONS] may appear.
 * Each name may appear one time only, and each name needs a value. The
 * check splits the query on "&" and on ";", because the MongoDB driver
 * accepts both separators. The check decodes a percent escape in each
 * option name before the compare. A percent-encoded evasion of the
 * allow-list thus fails too. Each error message names the rule and the
 * option name. It never names a value, and it never repeats a part of
 * the checked text.
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

        val seenNames = mutableSetOf<String>()
        for ((rawName, rawValue) in optionsOf(remainder)) {
            val name = decodedLowerName(rawName)
                ?: return invalid("An option name must use valid percent-encoding.")
            if (rawValue == null) {
                return invalid("The option $name needs a value.")
            }
            if (!seenNames.add(name)) {
                return invalid("The option $name must appear one time.")
            }
            when (name) {
                in ALLOWED_TRUE_ONLY_OPTIONS ->
                    if (rawValue != REQUIRED_TRUE_VALUE) {
                        return invalid("The option $name accepts only the value true.")
                    }
                in ALLOWED_OPTIONS_ANY_VALUE -> Unit
                else -> return invalid("The option $name is not on the allow-list of accepted options.")
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

    // A plain percent-decode of the option name only. It never touches "+",
    // because a connection string option name uses no form-encoding rule.
    // A malformed escape gives null, and the caller then rejects the URI.
    private fun decodedLowerName(rawName: String): String? {
        val decoded = StringBuilder()
        var index = 0
        while (index < rawName.length) {
            val char = rawName[index]
            if (char == '%') {
                if (index + 2 >= rawName.length) return null
                val byteValue = rawName.substring(index + 1, index + 3).toIntOrNull(16) ?: return null
                decoded.append(byteValue.toChar())
                index += 3
            } else {
                decoded.append(char)
                index += 1
            }
        }
        return decoded.toString().lowercase()
    }

    // The value stays as null when the pair has no "=", so the caller can
    // tell "no value" apart from "an empty value". D11 rejects both,
    // because a data class with a null String? already means "absent"
    // everywhere else in this module, and an empty value never matches
    // the required value "true" of an allow-listed option anyway.
    private fun optionsOf(remainder: String): List<Pair<String, String?>> {
        val queryStart = remainder.indexOf('?')
        if (queryStart == -1) return emptyList()
        val query = remainder.substring(queryStart + 1)
        return query.split('&', ';').mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val eq = pair.indexOf('=')
            if (eq == -1) pair to null else pair.substring(0, eq) to pair.substring(eq + 1)
        }
    }
}
