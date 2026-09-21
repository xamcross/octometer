package octometer.monitor.registry

// Rule of the implementer: a test file never holds a fake credential as
// one literal. Each function here joins the parts at run time. The
// gitleaks allowlist of .gitleaks.toml names the exact text of
// ALLOWLISTED_HOST together with the user and the word below. A test
// that also sends the same user and word stays inside the allowlist.

const val ALLOWLISTED_USER = "octotest"
const val ALLOWLISTED_WORD = "S3cr3t-Test-Only"

/** The host of the one allow-listed fake credential of .gitleaks.toml. */
const val ALLOWLISTED_HOST = "cluster0.example.mongodb.net"

/** A valid, accepted connection string, with the one allow-listed fake credential. */
fun allowlistedSrvUri(): String =
    "mongodb" + "+srv://" + ALLOWLISTED_USER + ":" + ALLOWLISTED_WORD + "@" + ALLOWLISTED_HOST

/** A second, valid, accepted connection string, with no embedded credential. */
fun allowlistedSrvUriWithoutCredential(option: String? = null): String {
    val base = "mongodb" + "+srv://" + ALLOWLISTED_HOST
    return if (option == null) base else "$base/?$option"
}

/** A rejected mongodb:// connection string, with a public host and no credential. */
fun publicHostUri(): String = "mongodb" + "://" + "example.com" + ":27017"

/** A loopback mongodb:// connection string, with no credential. */
fun loopbackUri(): String = "mongodb" + "://" + "localhost" + ":27017"

/**
 * BLOCKER 1 of the second security review: a raw "?" inside the password,
 * with no percent-escape. The parser then reads the tail of the password,
 * and the host after it, as the start of the query. A 400 body must never
 * carry any of those parts. The parts below let a test check for each one
 * on its own: the password word, a word only in the tail, and the host.
 */
fun srvUriWithRawQuestionMarkInPassword(): String =
    "mongodb" + "+srv://" + ALLOWLISTED_USER + ":" + ALLOWLISTED_WORD + "?" + PASSWORD_TAIL_AFTER_QUESTION_MARK +
        "@" + ALLOWLISTED_HOST

/** The part of [srvUriWithRawQuestionMarkInPassword] right after the raw "?". */
const val PASSWORD_TAIL_AFTER_QUESTION_MARK = "tail-of-the-password"
