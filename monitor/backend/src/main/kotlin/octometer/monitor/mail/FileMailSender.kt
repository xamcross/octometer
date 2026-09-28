package octometer.monitor.mail

import java.io.File
import java.security.MessageDigest
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Local, not octometer.monitor.accounts.sha256Hex: the mail package
// stays free of a dependency on the accounts package, the same rule
// that keeps mongo.sha256Hex private to its own module.
private fun sha256Hex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(value.toByteArray(Charsets.UTF_8))
    val hex = StringBuilder(hash.size * 2)
    for (byte in hash) {
        hex.append(Character.forDigit((byte.toInt() shr 4) and 0xF, 16))
        hex.append(Character.forDigit(byte.toInt() and 0xF, 16))
    }
    return hex.toString()
}

/**
 * The file implementation of [MailSender] for the end-to-end suite
 * (issue #301, D52): one file for each mail, under [mailDir]. No
 * third-party call ever runs in a test. [clock] is a test seam; the
 * production code keeps the system clock.
 */
class FileMailSender(
    private val mailDir: File,
    private val clock: Clock = Clock.systemUTC(),
) : MailSender {

    override suspend fun send(to: String, subject: String, text: String) {
        withContext(Dispatchers.IO) {
            mailDir.mkdirs()
            val fileName = "${clock.millis()}-${sha256Hex(to).take(8)}.txt"
            val content = "To: $to\nSubject: $subject\n\n$text"
            File(mailDir, fileName).writeText(content, Charsets.UTF_8)
        }
    }
}
