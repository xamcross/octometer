package octometer.monitor.registry

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The count bound of one app's origin list (issue #296, the correction M11). */
private const val MIN_ORIGIN_COUNT = 1
private const val MAX_ORIGIN_COUNT = 20

/** The length bound of one origin (issue #296, the correction M11), in the style of MAX_SHORT_FIELD_LENGTH. */
private const val MAX_ORIGIN_LENGTH = 253

/** The hosts that may use the scheme http (issue #296, the correction M11). */
private val HTTP_ONLY_HOSTS = setOf("localhost", "127.0.0.1")

private const val ORIGIN_COUNT_MESSAGE = "Give 1 to 20 origins."
private const val ORIGIN_FORM_MESSAGE = "An origin has the form https://host, without a path."
private const val ORIGIN_ASCII_MESSAGE = "Use the ASCII form of the host."
private const val ORIGIN_DUPLICATE_MESSAGE = "An origin appears twice."

/** The result of [OriginListValidator.check]. It never holds the checked text, beyond the fixed message. */
sealed class OriginsCheck {
    data class Valid(val origins: List<String>) : OriginsCheck()
    data class Invalid(val message: String) : OriginsCheck()
}

/**
 * The origin list check of issue #296 (design decisions D51, D52, the
 * correction of the brief review of 2026-10-01, rule M11). An origin has
 * the form `scheme://host[:port]`: the scheme `https`, or `http` only
 * for the hosts `localhost` and `127.0.0.1`; the host holds letters,
 * digits, a hyphen, a dot, or an IPv6 literal in brackets; no path, no
 * query, no fragment, no user info. The list holds 1 to 20 entries, each
 * a maximum of 253 characters, with no duplicate. The result keeps each
 * origin in lower case.
 */
object OriginListValidator {

    fun check(rawOrigins: List<String>): OriginsCheck {
        if (rawOrigins.size < MIN_ORIGIN_COUNT || rawOrigins.size > MAX_ORIGIN_COUNT) {
            return OriginsCheck.Invalid(ORIGIN_COUNT_MESSAGE)
        }
        val normalized = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (raw in rawOrigins) {
            val checked = checkOne(raw)
            if (checked is OneOriginCheck.Invalid) return OriginsCheck.Invalid(checked.message)
            val value = (checked as OneOriginCheck.Valid).origin
            if (!seen.add(value)) return OriginsCheck.Invalid(ORIGIN_DUPLICATE_MESSAGE)
            normalized += value
        }
        return OriginsCheck.Valid(normalized)
    }

    private sealed class OneOriginCheck {
        data class Valid(val origin: String) : OneOriginCheck()
        data class Invalid(val message: String) : OneOriginCheck()
    }

    private fun checkOne(raw: String): OneOriginCheck {
        if (raw.isEmpty() || raw.length > MAX_ORIGIN_LENGTH) return OneOriginCheck.Invalid(ORIGIN_FORM_MESSAGE)
        val schemeSep = raw.indexOf("://")
        if (schemeSep <= 0) return OneOriginCheck.Invalid(ORIGIN_FORM_MESSAGE)
        val scheme = raw.substring(0, schemeSep).lowercase()
        if (scheme != "https" && scheme != "http") return OneOriginCheck.Invalid(ORIGIN_FORM_MESSAGE)
        val rest = raw.substring(schemeSep + 3)
        if (rest.isEmpty() || rest.any { it == '/' || it == '?' || it == '#' || it == '@' }) {
            return OneOriginCheck.Invalid(ORIGIN_FORM_MESSAGE)
        }
        val (host, port) = splitHostPort(rest) ?: return OneOriginCheck.Invalid(ORIGIN_FORM_MESSAGE)
        if (host.any { it.code > 127 }) return OneOriginCheck.Invalid(ORIGIN_ASCII_MESSAGE)
        if (!isValidHostCharset(host)) return OneOriginCheck.Invalid(ORIGIN_FORM_MESSAGE)
        if (port != null && !isValidPort(port)) return OneOriginCheck.Invalid(ORIGIN_FORM_MESSAGE)
        val lowerHost = host.lowercase()
        if (scheme == "http" && lowerHost !in HTTP_ONLY_HOSTS) {
            return OneOriginCheck.Invalid(ORIGIN_FORM_MESSAGE)
        }
        val normalized = "$scheme://$lowerHost" + (port?.let { ":$it" } ?: "")
        return OneOriginCheck.Valid(normalized)
    }

    private fun isValidPort(port: String): Boolean =
        port.isNotEmpty() && port.all { it.isDigit() } && (port.toIntOrNull() ?: -1) in 0..65535

    // The host sits before the first ":" of the port, except an IPv6
    // literal in brackets, which may hold a colon of its own.
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

    private fun isValidHostCharset(host: String): Boolean {
        if (host.startsWith("[") && host.endsWith("]")) {
            val inner = host.substring(1, host.length - 1)
            return inner.isNotEmpty() && inner.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' }
        }
        return host.isNotEmpty() && host.all { it.isLetterOrDigit() || it == '-' || it == '.' }
    }
}

/** Encodes an origin list as the JSON text that the `app.origins` column stores (issue #296). */
internal fun encodeOriginsJson(origins: List<String>): String = Json.encodeToString(origins)

/** Decodes the `app.origins` column text back to a list. A blank or an unreadable value gives an empty list. */
internal fun decodeOriginsJson(text: String?): List<String> {
    if (text.isNullOrBlank()) return emptyList()
    return try {
        Json.decodeFromString<List<String>>(text)
    } catch (malformed: Exception) {
        emptyList()
    }
}
