package octometer.monitor.tracker

import java.io.File
import java.security.MessageDigest

/** The file name of the served tracker bundle inside the static folder (design decision D53, issue #298). */
internal const val TRACKER_FILE_NAME = "tracker.js"

/** The bytes of the served tracker bundle, with the SHA-256 hex hash of [bytes] (design decision D53). */
data class TrackerBundle(val bytes: ByteArray, val hash: String)

/**
 * Reads the served tracker bundle of design decision D53 (issue #298).
 * The backend's Gradle build copies `kit/tracker/dist/tracker.iife.js`
 * into the distribution's `static/tracker.js` (the brief review of
 * 2026-10-01, B5), the same static folder as the Angular build.
 */
object TrackerAssets {

    /**
     * Reads `tracker.js` from [staticDir] and its hash, or `null` when
     * [staticDir] is `null` (a dev-mode run with no distribution) or
     * holds no such file (a developer run with no `installDist`). The
     * caller then logs one start line and the route answers 404 (the
     * brief review of 2026-10-01, B5).
     */
    fun load(staticDir: File?): TrackerBundle? {
        if (staticDir == null) return null
        val file = File(staticDir, TRACKER_FILE_NAME)
        if (!file.isFile) return null
        val bytes = file.readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hash = digest.joinToString("") { byte -> "%02x".format(byte) }
        return TrackerBundle(bytes, hash)
    }
}
