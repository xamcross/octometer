package octometer.monitor

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import java.io.File
import java.nio.file.Files
import octometer.monitor.backup.backupsDir
import octometer.monitor.config.MonitorConfig

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

/** The dev mode config of the tests, with the configured port 7431. */
fun devConfig(dataDir: String = testDataDir(), backupDir: String = backupsDir(dataDir).absolutePath) = MonitorConfig(
    mode = "dev",
    port = 7431,
    dataDir = dataDir,
    settleLagSeconds = 2,
    retentionDays = 395,
    backupDir = backupDir,
)

/** The prod mode config of the tests, with the configured port 7431. */
fun prodConfig(dataDir: String = testDataDir(), backupDir: String = backupsDir(dataDir).absolutePath) = MonitorConfig(
    mode = "prod",
    port = 7431,
    dataDir = dataDir,
    settleLagSeconds = 60,
    retentionDays = 395,
    backupDir = backupDir,
)

/**
 * Sends the Host header that devConfig and prodConfig allow. Each test
 * needs it, because the test client sends no Host header by itself.
 */
fun HttpRequestBuilder.allowedHost() {
    header(HttpHeaders.Host, "localhost:7431")
}
