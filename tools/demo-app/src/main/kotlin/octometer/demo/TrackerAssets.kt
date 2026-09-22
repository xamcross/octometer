package octometer.demo

/**
 * Reads the built tracker of `kit/tracker` from the classpath (issue #14,
 * step 4). The build copies the built files of `kit/tracker/dist` into
 * `static/tracker/` of this module (`build.gradle.kts`, task
 * `copyTrackerDist`).
 */
object TrackerAssets {

    /** Each file name must match this pattern. It blocks a path with a slash or a dot segment. */
    val FILE_NAME_PATTERN: Regex = Regex("[A-Za-z0-9_.-]+\\.js")

    /**
     * Reads the tracker file `fileName` from the classpath, or `null`
     * when [fileName] breaks [FILE_NAME_PATTERN], or when the classpath
     * holds no such file.
     */
    fun read(fileName: String): ByteArray? {
        if (!FILE_NAME_PATTERN.matches(fileName)) {
            return null
        }
        val resourcePath = "static/tracker/$fileName"
        return TrackerAssets::class.java.classLoader.getResourceAsStream(resourcePath)?.use { it.readBytes() }
    }
}
