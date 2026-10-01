package octometer.monitor.mail

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private const val RESEND_URL = "https://api.resend.com/emails"

private val log = LoggerFactory.getLogger("octometer.monitor.mail.ResendMailSender")

@Serializable
private data class ResendEmailRequest(
    val from: String,
    val to: List<String>,
    val subject: String,
    val text: String,
)

/**
 * The Resend implementation of [MailSender] (issue #301, D52).
 * `java.net.http.HttpClient` needs no new dependency (JDK 21). A
 * non-2xx answer, or a network failure, logs one WARN line with the
 * status code, or the exception class, only; it never throws, so the
 * caller (and so the visitor) never learns whether the send failed.
 */
class ResendMailSender(
    private val apiKey: String,
    private val from: String,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) : MailSender {

    override suspend fun send(to: String, subject: String, text: String) {
        val body = Json.encodeToString(
            ResendEmailRequest.serializer(),
            ResendEmailRequest(from = from, to = listOf(to), subject = subject, text = text),
        )
        withContext(Dispatchers.IO) {
            try {
                val request = HttpRequest.newBuilder(URI.create(RESEND_URL))
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val response = httpClient.send(request, HttpResponse.BodyHandlers.discarding())
                if (response.statusCode() !in 200..299) {
                    log.warn("The mail send answered with the status {}.", response.statusCode())
                }
            } catch (failure: IOException) {
                log.warn("The mail send failed. {}", failure.javaClass.simpleName)
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                log.warn("The mail send failed. {}", failure.javaClass.simpleName)
            }
        }
    }
}
