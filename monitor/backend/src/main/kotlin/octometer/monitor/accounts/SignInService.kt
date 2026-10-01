package octometer.monitor.accounts

import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import octometer.monitor.mail.MailSender
import octometer.monitor.store.SqliteDatabase

/** The token lifetime of D52: 15 minutes. */
private const val SIGNIN_TOKEN_TTL_MILLIS = 15 * 60 * 1000L

/** The session lifetime of D52: 30 days. */
private const val SESSION_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000

/** The rate limit of D20, issue #301: 5 mails per address per hour. */
private const val ADDRESS_RATE_LIMIT = 5

/** The rate limit of D20, issue #301: 20 requests per client address per hour. */
private const val CLIENT_RATE_LIMIT = 20

private const val INVALID_EMAIL_MESSAGE = "Give a valid email address."
private const val MAIL_SUBJECT = "Your Octometer sign-in link"

/** The result of `POST /api/signin` (D52). [Accepted] is the one 202 answer of every well-formed address. */
sealed class SignInRequestResult {
    object Accepted : SignInRequestResult()
    data class InvalidRequest(val message: String) : SignInRequestResult()
}

/** The result of `POST /api/signin/confirm` (D52). */
sealed class SignInConfirmResult {
    data class Confirmed(val sessionToken: String, val expiresAtMillis: Long) : SignInConfirmResult()
    object Invalid : SignInConfirmResult()
}

/**
 * The sign-in service of issue #301, D52. [publicUrl] is the base of
 * the sign-in link; a blank value falls back to a loopback link
 * (`monitor.Application`, brief review B3) that the caller resolves
 * before it builds this service.
 *
 * [requestSignIn] answers first, then sends the mail on its own
 * coroutine (brief review M6): the caller (the route) returns to the
 * client as soon as this function returns, well before a slow mail
 * call ends. [close] cancels that scope; `MonitorServices.close()`
 * calls it, so no mail send outlives the application.
 */
class SignInService(
    private val database: SqliteDatabase,
    private val mailSender: MailSender,
    private val publicUrl: String,
    private val clock: Clock = Clock.systemUTC(),
    private val addressRateLimiter: FixedWindowRateLimiter =
        FixedWindowRateLimiter(RATE_LIMIT_WINDOW_MILLIS, ADDRESS_RATE_LIMIT),
    private val clientRateLimiter: FixedWindowRateLimiter =
        FixedWindowRateLimiter(RATE_LIMIT_WINDOW_MILLIS, CLIENT_RATE_LIMIT),
) : AutoCloseable {

    private val mailScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * `POST /api/signin`. Every well-formed address gets [SignInRequestResult.Accepted],
     * listed or not, also above a rate limit (brief review M6): the
     * answer never tells the two cases apart. The per-address counter
     * counts every call, listed or not.
     */
    suspend fun requestSignIn(rawEmail: String, clientAddress: String): SignInRequestResult {
        val email = normalizeEmail(rawEmail)
        if (!isWellFormedEmail(email)) return SignInRequestResult.InvalidRequest(INVALID_EMAIL_MESSAGE)

        val now = clock.millis()
        val underAddressLimit = addressRateLimiter.recordAndCheck(email, now)
        val underClientLimit = clientRateLimiter.recordAndCheck(clientAddress, now)

        if (underAddressLimit && underClientLimit) {
            val listed = database.read { reader -> isInvited(reader, email) }
            if (listed) {
                sendSignInMail(email, now)
            }
        }
        return SignInRequestResult.Accepted
    }

    private suspend fun sendSignInMail(email: String, now: Long) {
        val token = newToken()
        val tokenHash = sha256Hex(token)
        val expiresAt = now + SIGNIN_TOKEN_TTL_MILLIS
        database.write { writer -> insertSignInToken(writer, tokenHash, email, expiresAt, now) }
        val link = "$publicUrl/signin?token=$token"
        val text = "Sign in to Octometer. Open this link within 15 minutes: $link. " +
            "If you did not request it, ignore this mail."
        // Brief review M6: the mail send runs after the 202 answer. The
        // caller (SignInRoutes) awaits this function only up to here;
        // the launch below returns at once, and the send itself runs on
        // its own coroutine.
        mailScope.launch { mailSender.send(email, MAIL_SUBJECT, text) }
    }

    /**
     * `POST /api/signin/confirm`. A valid, unused, unexpired token whose
     * address still sits on the invite list creates the account when it
     * is absent, marks the token used, and creates the session. A
     * removed invite makes an old token invalid too.
     */
    suspend fun confirm(rawToken: String): SignInConfirmResult {
        val tokenHash = sha256Hex(rawToken)
        val now = clock.millis()
        return database.write { writer ->
            val row = findSignInToken(writer, tokenHash)
            if (row == null || row.usedAt != null || row.expiresAt < now || !isInvited(writer, row.email)) {
                return@write SignInConfirmResult.Invalid
            }
            markSignInTokenUsed(writer, tokenHash, now)
            val accountId = findOrCreateAccountId(writer, row.email, now)
            val sessionToken = newToken()
            val sessionHash = sha256Hex(sessionToken)
            val expiresAt = now + SESSION_TTL_MILLIS
            insertSession(writer, sessionHash, accountId, expiresAt, now)
            SignInConfirmResult.Confirmed(sessionToken, expiresAt)
        }
    }

    /** `POST /api/signout`: deletes the session row. A missing or an unknown token changes nothing. */
    suspend fun signOut(rawSessionToken: String?) {
        if (rawSessionToken == null) return
        database.write { writer -> deleteSession(writer, sha256Hex(rawSessionToken)) }
    }

    /** `GET /api/me`: the email address of the signed-in account. */
    suspend fun accountEmail(accountId: Long): String? =
        database.read { reader -> findAccountEmail(reader, accountId) }

    override fun close() {
        mailScope.cancel()
    }
}
