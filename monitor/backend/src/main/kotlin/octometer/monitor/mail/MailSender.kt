package octometer.monitor.mail

/**
 * The one mail interface of issue #301, D52. [send] never throws for a
 * mail-service failure: the caller must learn nothing that tells a
 * listed address apart from an unlisted one.
 */
interface MailSender {
    suspend fun send(to: String, subject: String, text: String)
}
