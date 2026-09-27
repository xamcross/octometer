package octometer.monitor.lock

import java.io.File
import java.nio.file.Files
import octometer.monitor.registerTempRoot
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

// Decision 2 of issue #58, the correction of 2026-09-27: a Gradle test
// worker holds only its own worker jar on java.class.path, so a
// ProcessBuilder with that class path cannot start a second copy of
// LockProbe by its class name. FileChannel.tryLock needs no class path
// at all, so this test launches one small Java source file directly
// (`java LockProbe.java <path>`, a single-file source launch, JDK 11 and
// later), the same pattern as StaticFrontendTraversalTest's own
// ProcessBuilder call for a Windows-only tool.
class InstanceLockTest {

    private val root = Files.createTempDirectory("octometer-instance-lock-test-").toFile().also { registerTempRoot(it) }
    private val lockFile = File(root, LOCK_FILE_NAME)

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `a second process fails the lock while the first process holds it, and the lock releases on close`() {
        val probe = writeLockProbeSource(root)

        val instanceLock = InstanceLock.acquire(root.absolutePath)
        try {
            val exitCodeWhileHeld = runLockProbe(probe, lockFile)
            assertEquals(1, exitCodeWhileHeld, "a second process must fail the lock while the first holds it")
        } finally {
            instanceLock.close()
        }

        val exitCodeAfterClose = runLockProbe(probe, lockFile)
        assertEquals(0, exitCodeAfterClose, "the lock releases on close, so a later process gets it")
    }

    @Test
    fun `InstanceLock acquire throws InstanceLockFailedException with the file name only, never the folder path`() {
        val probe = writeLockProbeSource(root)
        val probeProcess = ProcessBuilder(javaLauncher(), probe.absolutePath, lockFile.absolutePath, "5000")
            .redirectErrorStream(true)
            .start()
        try {
            awaitLockFileCreated(lockFile)

            val failure = assertFailsWith<InstanceLockFailedException> {
                InstanceLock.acquire(root.absolutePath)
            }

            assertFalse(failure.message!!.contains(root.absolutePath), "the message names a file name only")
        } finally {
            probeProcess.destroy()
            probeProcess.waitFor()
        }
    }
}

private fun awaitLockFileCreated(lockFile: File) {
    val deadline = System.currentTimeMillis() + 5_000
    while (!lockFile.exists() && System.currentTimeMillis() < deadline) {
        Thread.sleep(20)
    }
}

/** `<java.home>/bin/java[.exe]`, so the probe needs no `java` on PATH. */
private fun javaLauncher(): String {
    val javaHome = System.getProperty("java.home")
    val exeName = if (System.getProperty("os.name").lowercase().contains("windows")) "java.exe" else "java"
    return File(File(javaHome, "bin"), exeName).absolutePath
}

/** Runs the probe once, with no hold time, and gives its exit code. */
private fun runLockProbe(probe: File, lockFile: File): Int {
    val process = ProcessBuilder(javaLauncher(), probe.absolutePath, lockFile.absolutePath, "0")
        .redirectErrorStream(true)
        .start()
    process.waitFor()
    return process.exitValue()
}

/**
 * Writes LockProbe.java into [root]. The probe takes the lock file path
 * and a hold time in milliseconds; exit code 0 means it got the lock,
 * exit code 1 means `tryLock()` gave null (another process holds it).
 */
private fun writeLockProbeSource(root: File): File {
    val source = """
        import java.io.File;
        import java.nio.channels.FileChannel;
        import java.nio.channels.FileLock;
        import java.nio.file.StandardOpenOption;

        public class LockProbe {
            public static void main(String[] args) throws Exception {
                File lockFile = new File(args[0]);
                long holdMillis = Long.parseLong(args[1]);
                try (FileChannel channel = FileChannel.open(
                        lockFile.toPath(),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE)) {
                    FileLock lock = channel.tryLock();
                    if (lock == null) {
                        System.exit(1);
                    }
                    if (holdMillis > 0) {
                        Thread.sleep(holdMillis);
                    }
                    lock.release();
                    System.exit(0);
                }
            }
        }
    """.trimIndent()
    val file = File(root, "LockProbe.java")
    file.writeText(source)
    return file
}
