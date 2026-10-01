package octometer.monitor

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import octometer.monitor.accounts.SESSION_COOKIE_NAME
import octometer.monitor.accounts.newToken
import octometer.monitor.accounts.sha256Hex
import octometer.monitor.backup.backupsDir
import octometer.monitor.config.MonitorConfig
import octometer.monitor.mail.mailDir as defaultMailDir

// One shared config pair for the tests of this module. HealthRouteTest and
// RequestGuardTest held their own copy before; this file removes the
// duplication.

// MAJOR 9 (Ktor review): a test run never deleted a temporary root. Two
// runs then left many folders in the temp folder of the machine. Each
// call to testDataDir() registers its root here. The shutdown hook
// deletes each root when the test JVM ends, on a pass and on a failure.
// A test with its own folder outside testDataDir() can register that
// folder too, with registerTempRoot(root).
private val tempRoots = mutableListOf<File>()

private val cleanupHookRegistered: Boolean = run {
    Runtime.getRuntime().addShutdownHook(
        Thread {
            synchronized(tempRoots) {
                for (root in tempRoots) {
                    root.deleteRecursively()
                }
            }
        },
    )
    true
}

/** Registers [root] for deletion when the test JVM ends. */
fun registerTempRoot(root: File) {
    check(cleanupHookRegistered)
    synchronized(tempRoots) { tempRoots += root }
}

/**
 * A fresh temporary folder for one test, never the real data folder.
 * module() opens a real SqliteDatabase in config.dataDir (issue #15). A
 * config for a test must never name a real folder, for example
 * "C:/data". The parent of the returned folder is a folder of its own.
 * A test that also reads the sibling "secrets" folder of D34 thus never
 * shares it with a different test.
 */
fun testDataDir(): String {
    val root = Files.createTempDirectory("octometer-test-").toFile()
    registerTempRoot(root)
    return File(root, "data").absolutePath
}

// SQLite MAJOR A of correction round 2: a test with its own registered
// [root] must nest its data folder too, the same rule as testDataDir().
// A call with the root itself as dataDir puts the default backupDir at
// the sibling "backups" folder of the root, thus outside the root.

/** The nested data folder of an already registered [root]. It makes the folder. */
fun testDataDir(root: File): String {
    val dataDir = File(root, "data")
    dataDir.mkdirs()
    return dataDir.absolutePath
}

// SQLite MAJOR 1 of correction round 1: backupDir defaults to the sibling
// "backups" folder of dataDir, the same rule as the production default.
// A test that gives testDataDir() its nested "data" folder thus keeps its
// backups inside the one registered root, never at the system temp root.

// Correction round 1 of issue #59 (MAJOR 3, SQLite review): MonitorServices
// now runs a retention purge at each open call, on the real clock. A large
// default keeps each fixed test fixture. A test of the purge itself passes
// its own small retentionDays.
private const val LARGE_TEST_RETENTION_DAYS = 3_650_000

/** The dev mode config of the tests, with the configured port 7431. */
fun devConfig(
    dataDir: String = testDataDir(),
    backupDir: String = backupsDir(dataDir).absolutePath,
    retentionDays: Int = LARGE_TEST_RETENTION_DAYS,
    pollIntervalSeconds: Int = 5,
    publicUrl: String = "",
    mailSender: String = "file",
    mailDir: String = defaultMailDir(dataDir).absolutePath,
) = MonitorConfig(
    mode = "dev",
    port = 7431,
    dataDir = dataDir,
    settleLagSeconds = 2,
    retentionDays = retentionDays,
    backupDir = backupDir,
    pollIntervalSeconds = pollIntervalSeconds,
    publicUrl = publicUrl,
    mailSender = mailSender,
    mailDir = mailDir,
)

/** The prod mode config of the tests, with the configured port 7431. */
fun prodConfig(
    dataDir: String = testDataDir(),
    backupDir: String = backupsDir(dataDir).absolutePath,
    retentionDays: Int = LARGE_TEST_RETENTION_DAYS,
    pollIntervalSeconds: Int = 60,
    publicUrl: String = "",
    mailSender: String = "file",
    mailDir: String = defaultMailDir(dataDir).absolutePath,
) = MonitorConfig(
    mode = "prod",
    port = 7431,
    dataDir = dataDir,
    settleLagSeconds = 60,
    retentionDays = retentionDays,
    backupDir = backupDir,
    pollIntervalSeconds = pollIntervalSeconds,
    publicUrl = publicUrl,
    mailSender = mailSender,
    mailDir = mailDir,
)

/**
 * Sends the Host header that devConfig and prodConfig allow. Each test
 * needs it, because the test client sends no Host header by itself.
 */
fun HttpRequestBuilder.allowedHost() {
    header(HttpHeaders.Host, "localhost:7431")
}

/**
 * Issue #301, D52: `/api/` now needs a session. This inserts an
 * account and a valid session straight into the SQLite file at
 * [dataDir], through a raw JDBC connection of its own (WAL mode admits
 * a second writer). A test calls this only after `application { module(config) }`
 * has started, so the migrations of `octometer.db` already ran. It
 * returns the `Cookie` header value of that session, for
 * [HttpRequestBuilder.withSession].
 */
fun sessionCookieValue(dataDir: String, email: String = "test-account@example.com"): String =
    sessionCookieValueWithAccountId(dataDir, email).first

/**
 * The same insert as [sessionCookieValue], plus the new account id: a
 * test that also seeds a row of its own (for example an app row) with
 * that same `account_id` needs it, so the session's own `GET /api/apps`
 * lists that row back (issue #301).
 */
fun sessionCookieValueWithAccountId(dataDir: String, email: String = "test-account@example.com"): Pair<String, Long> {
    val connection = openTestConnection(dataDir)
    try {
        val now = System.currentTimeMillis()
        // A second call for the same dataDir and the same email (a test
        // that opens and closes the server more than once in one
        // folder) reuses the existing account row, so the UNIQUE rule
        // of account.email never fails a second call.
        val existingAccountId = connection.prepareStatement("SELECT id FROM account WHERE email = ?").use { select ->
            select.setString(1, email)
            select.executeQuery().use { result -> if (result.next()) result.getLong(1) else null }
        }
        val accountId = existingAccountId ?: run {
            connection.prepareStatement("INSERT INTO account (email, created_at) VALUES (?, ?)").use { insert ->
                insert.setString(1, email)
                insert.setLong(2, now)
                insert.executeUpdate()
            }
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT last_insert_rowid()").use { result ->
                    result.next()
                    result.getLong(1)
                }
            }
        }
        val token = newToken()
        connection.prepareStatement(
            "INSERT INTO session (session_hash, account_id, expires_at, created_at) VALUES (?, ?, ?, ?)",
        ).use { insert ->
            insert.setString(1, sha256Hex(token))
            insert.setLong(2, accountId)
            insert.setLong(3, now + 30L * 24 * 60 * 60 * 1000)
            insert.setLong(4, now)
            insert.executeUpdate()
        }
        return "$SESSION_COOKIE_NAME=$token" to accountId
    } finally {
        connection.close()
    }
}

/** Adds the `Cookie` header of [sessionCookieValue], so a request reaches a route behind the session check. */
fun HttpRequestBuilder.withSession(cookieValue: String) {
    header(HttpHeaders.Cookie, cookieValue)
}

// A short-lived JDBC connection of a test helper, beside the app's own
// writer connection on the same file (SQLite WAL admits a second
// writer). busy_timeout=5000 matches SqlitePragmas.DEFAULT_BUSY_TIMEOUT_MILLIS,
// so a brief lock of the app's own writer thread (a migration, a poll
// cycle write) makes this connection wait, not fail with SQLITE_BUSY.
//
// Issue #301: Ktor's testApplication starts the module lazily, on the
// first client request, not at the `application { }` call. A caller
// that seeds a session right after `application { module(config) }`,
// before any request, can then reach this function before
// SqliteDatabase.open ever created octometer.db. The retry loop below
// waits up to five seconds for that file, the same bound as the other
// poll loops of this test module.
private fun openTestConnection(dataDir: String): java.sql.Connection {
    val dbFile = File(dataDir, "octometer.db")
    val deadline = System.nanoTime() + 5_000_000_000L
    while (!dbFile.isFile && System.nanoTime() < deadline) {
        Thread.sleep(20)
    }
    var lastFailure: java.sql.SQLException? = null
    while (System.nanoTime() < deadline) {
        try {
            val connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.absolutePath)
            connection.createStatement().use { it.execute("PRAGMA busy_timeout=5000") }
            return connection
        } catch (failure: java.sql.SQLException) {
            lastFailure = failure
            Thread.sleep(20)
        }
    }
    throw lastFailure ?: java.sql.SQLException("octometer.db never appeared at $dataDir")
}

/**
 * The same session insert as [sessionCookieValue], for an [accountId]
 * that a test already made (for example with its own account row and
 * its own app rows, through a raw SQL insert of its own).
 */
fun sessionCookieValueForAccount(dataDir: String, accountId: Long): String {
    val connection = openTestConnection(dataDir)
    try {
        val now = System.currentTimeMillis()
        val token = newToken()
        connection.prepareStatement(
            "INSERT INTO session (session_hash, account_id, expires_at, created_at) VALUES (?, ?, ?, ?)",
        ).use { insert ->
            insert.setString(1, sha256Hex(token))
            insert.setLong(2, accountId)
            insert.setLong(3, now + 30L * 24 * 60 * 60 * 1000)
            insert.setLong(4, now)
            insert.executeUpdate()
        }
        return "$SESSION_COOKIE_NAME=$token"
    } finally {
        connection.close()
    }
}
