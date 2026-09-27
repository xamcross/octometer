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

// Issue #272: the registry derives the database name from the path of
// the connection string. This appends one path segment to a fixture
// that has none yet, right before its query string, when it has one, so
// a test can still name one database for a create or a PATCH.
fun withDatabasePath(connectionString: String, databaseName: String = "db"): String {
    val queryStart = connectionString.indexOf('?')
    val beforeQuery = if (queryStart == -1) connectionString else connectionString.substring(0, queryStart)
    val query = if (queryStart == -1) "" else connectionString.substring(queryStart)
    val afterScheme = beforeQuery.substringAfter("://")
    val hasPath = afterScheme.contains('/')
    return if (hasPath) connectionString else "$beforeQuery/$databaseName$query"
}

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
