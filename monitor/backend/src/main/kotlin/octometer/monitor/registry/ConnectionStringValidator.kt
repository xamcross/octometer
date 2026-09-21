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

    /** [message] names the failed rule, as one fixed sentence. It never repeats the checked text. */
    data class Invalid(val message: String) : ConnectionStringCheck()
}

// NEW DECISION of the maintainer, second correction round: each rule below
// has one fixed sentence. A message never carries any text of the checked
// URI: not an option name, not a value, not a host. A password that holds
// a raw "?" makes the parser read part of the password as an option name.
// It also reads the host after that mark as an option name. The old
// messages then leaked that part in the 400 body. A fixed sentence per
// rule closes this for every rule, not only for the one that a probe
// happens to find.
private const val MESSAGE_SCHEME = "The scheme must be mongodb+srv:// or mongodb://."
private const val MESSAGE_PUBLIC_HOST = "A mongodb:// connection string needs a loopback host."
private const val MESSAGE_PERCENT_ENCODING = "An option name must use valid percent-encoding."
private const val MESSAGE_NO_VALUE = "Each option of a connection string needs a value."
private const val MESSAGE_DUPLICATE = "An option name must appear one time only."
private const val MESSAGE_TLS_VALUE = "The tls and ssl options accept only the value true."
private const val MESSAGE_OPTION_NOT_ALLOWED = "An option name is not on the allow-list of accepted options."

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
 * allow-list thus fails too.
 *
 * Each rule has one fixed message (see the constants above). No message
 * carries an option name, a value, or a host of the checked text.
 */
object ConnectionStringValidator {

    fun check(connectionString: String): ConnectionStringCheck {
        val prefix = when {
            connectionString.startsWith(SRV_PREFIX) -> SRV_PREFIX
            connectionString.startsWith(STANDARD_PREFIX) -> STANDARD_PREFIX
            else -> return invalid(MESSAGE_SCHEME)
        }

        val remainder = connectionString.substring(prefix.length)

        if (prefix == STANDARD_PREFIX && !isLoopbackOnly(hostSegmentOf(remainder))) {
            return invalid(MESSAGE_PUBLIC_HOST)
        }

        val seenNames = mutableSetOf<String>()
        for ((rawName, rawValue) in optionsOf(remainder)) {
            val name = decodedLowerName(rawName) ?: return invalid(MESSAGE_PERCENT_ENCODING)
            if (rawValue == null) {
                return invalid(MESSAGE_NO_VALUE)
            }
            if (!seenNames.add(name)) {
                return invalid(MESSAGE_DUPLICATE)
            }
            when (name) {
                in ALLOWED_TRUE_ONLY_OPTIONS ->
                    if (!rawValue.equals(REQUIRED_TRUE_VALUE, ignoreCase = true)) {
                        return invalid(MESSAGE_TLS_VALUE)
                    }
                in ALLOWED_OPTIONS_ANY_VALUE -> Unit
                else -> return invalid(MESSAGE_OPTION_NOT_ALLOWED)
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
