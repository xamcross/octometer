package octometer.monitor.mail

import java.net.URI
import java.net.URISyntaxException
import octometer.monitor.config.InvalidConfigException
import octometer.monitor.config.MonitorConfig

/**
 * Refuses the start with `mailSender=file` on a public host (issue
 * #301, brief review m5): a mail written to the local disk on a
 * hosted machine would never reach the visitor. A blank `publicUrl`
 * passes (brief review B3: the local run then uses the loopback link
 * base); a `localhost` or `127.0.0.1` host passes too.
 *
 * @throws InvalidConfigException the sender is `file`, and the host of
 *   `publicUrl` is a real, public host.
 */
fun requireLocalMailSender(config: MonitorConfig) {
    if (config.mailSender != "file") return
    if (config.publicUrl.isBlank()) return
    val host = try {
        URI(config.publicUrl).host?.lowercase()
    } catch (malformed: URISyntaxException) {
        null
    }
    if (host == "localhost" || host == "127.0.0.1") return
    throw InvalidConfigException(
        "The value of mailSender is 'file', and the host of publicUrl is not a loopback host. " +
            "Set mailSender to 'resend', or set publicUrl to a loopback host.",
    )
}
