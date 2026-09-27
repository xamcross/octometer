package octometer.monitor.lock

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import octometer.monitor.registerTempRoot
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

// Decision 2 of issue #58, the correction of 2026-09-27: a Gradle test
// worker holds only its own worker jar on java.class.path, so a
// ProcessBuilder with that class path cannot start a second copy of
// LockProbe by its class name. FileChannel.tryLock needs no class path
// at all, so this test launches one small Java source file directly
// (`java LockProbe.java <path>`, a single-file source launch, JDK 11 and
// later), the same pattern as StaticFrontendTraversalTest's own
// ProcessBuilder call for a Windows-only tool.
//
// Correction round 1 (MAJOR C, both reviews): the probe now prints one
// marker line, LOCKED_MARKER or NOT_LOCKED_MARKER, right after
// tryLock() gives its answer. The old form waited for the lock file to
// exist, not for the held lock; FileChannel.open's own CREATE option
// makes that file before tryLock() ever runs, so the old wait could
// pass before the probe held the lock, and the test failed at random.
// The marker also catches a probe that never compiles: a compile
// failure of LockProbe.java can give the same exit code 1 as a denied
// lock, with no marker line at all; awaitMarkerLine fails on a missing
// marker, never on a bare timeout that lets the test pass by chance.
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
            val (exitCodeWhileHeld, outputWhileHeld) = runLockProbeToCompletion(probe, lockFile)
            assertEquals(1, exitCodeWhileHeld, "a second process must fail the lock while the first holds it")
            assertTrue(
                outputWhileHeld.contains(NOT_LOCKED_MARKER),
                "the probe must report $NOT_LOCKED_MARKER, not a silent or a compile failure: $outputWhileHeld",
            )
        } finally {
            instanceLock.close()
        }

        val (exitCodeAfterClose, outputAfterClose) = runLockProbeToCompletion(probe, lockFile)
        assertEquals(0, exitCodeAfterClose, "the lock releases on close, so a later process gets it")
        assertTrue(
            outputAfterClose.contains(LOCKED_MARKER),
            "the probe must report $LOCKED_MARKER, not a silent or a compile failure: $outputAfterClose",
        )
    }

    @Test
    fun `InstanceLock acquire throws InstanceLockFailedException with the file name only, never the folder path`() {
        val probe = writeLockProbeSource(root)
        val probeProcess = ProcessBuilder(javaLauncher(), probe.absolutePath, lockFile.absolutePath, "8000")
            .redirectErrorStream(true)
            .start()
        try {
            awaitMarkerLine(probeProcess, LOCKED_MARKER, boundMillis = 10_000)

            val failure = assertFailsWith<InstanceLockFailedException> {
                InstanceLock.acquire(root.absolutePath)
            }

            assertFalse(failure.message!!.contains(root.absolutePath), "the message names a file name only")
        } finally {
            probeProcess.destroy()
            probeProcess.waitFor()
        }
    }

    // Correction round 1, MAJOR A (the ops MAJOR 1, and the Kotlin
    // MINOR 4, on the same lines): FileChannel.open used to sit outside
    // the try block, so a dataDir that the process cannot open gave an
    // uncaught NoSuchFileException with the full path. A regular file
    // in place of the parent folder gives the same "not a directory"
    // failure, with no dependence on a real missing drive letter.
    @Test
    fun `InstanceLock acquire throws InstanceLockFailedException when the data folder is not available`() {
        val notADirectory = File(root, "a-file-not-a-folder")
        notADirectory.writeText("this is a file, not a folder")
        val unopenableDataDir = File(notADirectory, "data").absolutePath

        val failure = assertFailsWith<InstanceLockFailedException> {
            InstanceLock.acquire(unopenableDataDir)
        }

        assertEquals("The data folder is not available.", failure.message)
        assertFalse(failure.message!!.contains(unopenableDataDir), "the message names no folder path")
    }
}

private const val LOCKED_MARKER = "locked"
private const val NOT_LOCKED_MARKER = "not-locked"

/**
 * Waits, with the bound [boundMillis], for [process] to print one line
 * that equals [marker]. A reader thread reads each line as it arrives,
 * so this call sees the marker while [process] still runs (a held
 * lock's own process sleeps before it exits). Fails the test, with a
 * clear message, when the bound passes with no marker line; the caller
 * never mistakes a silent timeout for a passed check (MAJOR C, both
 * reviews of pull request #233).
 */
private fun awaitMarkerLine(process: Process, marker: String, boundMillis: Long) {
    val found = CompletableFuture<Boolean>()
    val readerThread = Thread {
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.trim() == marker) {
                        found.complete(true)
                        return@Thread
                    }
                }
            }
            found.complete(false)
        } catch (ioFailure: IOException) {
            found.completeExceptionally(ioFailure)
        }
    }
    readerThread.isDaemon = true
    readerThread.start()
    val arrived = try {
        found.get(boundMillis, TimeUnit.MILLISECONDS)
    } catch (timeout: TimeoutException) {
        false
    }
    if (!arrived) {
        fail("the probe gave no '$marker' line within $boundMillis ms")
    }
}

/** `<java.home>/bin/java[.exe]`, so the probe needs no `java` on PATH. */
private fun javaLauncher(): String {
    val javaHome = System.getProperty("java.home")
    val exeName = if (System.getProperty("os.name").lowercase().contains("windows")) "java.exe" else "java"
    return File(File(javaHome, "bin"), exeName).absolutePath
}

/**
 * Runs the probe once, with no hold time, waits for it to end, and
 * gives its exit code with its full standard output (MAJOR C: the
 * caller asserts the marker line of that output, not the exit code
 * alone, so a compile failure of LockProbe.java cannot pass as a
 * denied lock by chance).
 */
private fun runLockProbeToCompletion(probe: File, lockFile: File): Pair<Int, String> {
    val process = ProcessBuilder(javaLauncher(), probe.absolutePath, lockFile.absolutePath, "0")
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    process.waitFor()
    return process.exitValue() to output
}

/**
 * Writes LockProbe.java into [root]. The probe takes the lock file path
 * and a hold time in milliseconds. It prints [LOCKED_MARKER] right
 * after a successful `tryLock()`, or [NOT_LOCKED_MARKER] right after a
 * denied one (MAJOR C); exit code 0 means it got the lock, exit code 1
 * means `tryLock()` gave null (another process holds it).
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
                        System.out.println("$NOT_LOCKED_MARKER");
                        System.out.flush();
                        System.exit(1);
                    }
                    System.out.println("$LOCKED_MARKER");
                    System.out.flush();
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
