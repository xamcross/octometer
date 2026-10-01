package octometer.monitor.accounts

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

// D51, issue #301: the sign-in token and the session id use the same
// form as the app key and the secret key: 32 random bytes from
// SecureRandom, base64url without padding. The store holds the SHA-256
// hash only. The lookup compares in constant time.

private const val TOKEN_BYTE_LENGTH = 32

private val secureRandom = SecureRandom()
private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

/** A fresh 32-byte random value, base64url without padding (D51). */
fun newToken(): String {
    val bytes = ByteArray(TOKEN_BYTE_LENGTH)
    secureRandom.nextBytes(bytes)
    return urlEncoder.encodeToString(bytes)
}

/** The SHA-256 hash of [value], as lower-case hex. The store never holds the plain value. */
fun sha256Hex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(value.toByteArray(Charsets.UTF_8))
    val hex = StringBuilder(hash.size * 2)
    for (byte in hash) {
        hex.append(Character.forDigit((byte.toInt() shr 4) and 0xF, 16))
        hex.append(Character.forDigit(byte.toInt() and 0xF, 16))
    }
    return hex.toString()
}

/** Compares two hex hashes in constant time (D51), so a timing difference reveals nothing. */
fun constantTimeHashEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
