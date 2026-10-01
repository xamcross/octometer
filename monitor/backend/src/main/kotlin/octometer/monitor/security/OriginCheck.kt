package octometer.monitor.security

/**
 * The origin check of issue #296 (D52): the collector route of issue
 * #297 compares the `Origin` header of one request against the origin
 * list of one app. This story adds the helper and its own case list
 * only; #297 is the one caller.
 *
 * [matches] compares the scheme, the host, and the port, each after
 * lower-casing. The port is implicit when absent: `https://host`
 * matches `https://host:443`, and `http://host` matches `http://host:80`.
 * An absent header gives `false`.
 */
object OriginCheck {

    fun matches(originHeader: String?, origins: List<String>): Boolean {
        if (originHeader == null) return false
        val normalizedHeader = normalized(originHeader) ?: return false
        return origins.any { normalized(it) == normalizedHeader }
    }

    private fun normalized(origin: String): String? {
        val schemeSep = origin.indexOf("://")
        if (schemeSep <= 0) return null
        val scheme = origin.substring(0, schemeSep).lowercase()
        val defaultPort = when (scheme) {
            "https" -> "443"
            "http" -> "80"
            else -> return null
        }
        val rest = origin.substring(schemeSep + 3)
        if (rest.isEmpty()) return null
        val (host, port) = splitHostPort(rest) ?: return null
        return "$scheme://${host.lowercase()}:${port ?: defaultPort}"
    }

    // The host sits before the first ":" of the port, except an IPv6
    // literal in brackets, which may hold a colon of its own (the same
    // rule as OriginListValidator.splitHostPort).
    private fun splitHostPort(rest: String): Pair<String, String?>? {
        if (rest.startsWith("[")) {
            val end = rest.indexOf(']')
            if (end == -1) return null
            val host = rest.substring(0, end + 1)
            val afterBracket = rest.substring(end + 1)
            return when {
                afterBracket.isEmpty() -> host to null
                afterBracket.startsWith(":") && afterBracket.length > 1 -> host to afterBracket.substring(1)
                else -> null
            }
        }
        val colon = rest.indexOf(':')
        return if (colon == -1) rest to null else rest.substring(0, colon) to rest.substring(colon + 1)
    }
}
