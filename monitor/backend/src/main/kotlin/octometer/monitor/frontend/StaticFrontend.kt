package octometer.monitor.frontend

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.io.File
import java.io.IOException
import java.net.URISyntaxException

private const val INDEX_FILE_NAME = "index.html"
private const val INDEX_CACHE_CONTROL = "no-cache"
private const val HASHED_ASSET_CACHE_CONTROL = "public, max-age=31536000, immutable"

// The Angular production build (design decision D27, outputHashing "all")
// names a bundle file with a dash and a hash before the extension, for
// example "main-GES6WX3U.js". A plain asset, for example
// "public/favicon.ico", keeps its own name and matches no such pattern.
private val HASHED_FILE_NAME = Regex("""^.+-[0-9A-Za-z]{8,}\.[0-9a-z]+$""")

/**
 * Serves the Angular build of `monitor/frontend` (issue #38, D28). The
 * caller adds this route only when [staticDir] exists.
 *
 * A request for a real file under [staticDir] gets that file. Each other
 * request gets `index.html`, because the Angular router reads the
 * browser path, not the server (D28, the path router). The route
 * [octometer.monitor.apiRoutes] adds for an unknown `/api/` path answers
 * first, so this route never serves `index.html` for a path below
 * `/api/`.
 *
 * `index.html` goes out with `Cache-Control: no-cache`, so a new release
 * reaches the browser at once. A hashed file name marks an immutable
 * file, so it gets a cache time of one year.
 */
fun Route.staticFrontend(staticDir: File) {
    get("/{path...}") {
        val segments = call.parameters.getAll("path").orEmpty()
        val fileToServe = resolveRequestedFile(staticDir, segments) ?: File(staticDir, INDEX_FILE_NAME)
        respondStaticFile(call, fileToServe)
    }
}

private suspend fun respondStaticFile(call: ApplicationCall, file: File) {
    val cacheControl = if (file.name != INDEX_FILE_NAME && HASHED_FILE_NAME.matches(file.name)) {
        HASHED_ASSET_CACHE_CONTROL
    } else {
        INDEX_CACHE_CONTROL
    }
    call.response.header(HttpHeaders.CacheControl, cacheControl)
    call.respondFile(file)
}

// Turns the browser path into a real file under staticDir, or gives null
// when no such file exists, for example the Angular route "/apps". The
// canonical-path check stops a segment such as ".." from leaving
// staticDir (path traversal).
private fun resolveRequestedFile(staticDir: File, segments: List<String>): File? {
    if (segments.isEmpty() || segments.all { it.isEmpty() }) return null
    val candidate = segments.fold(staticDir) { dir, segment -> File(dir, segment) }
    val canonicalRoot = staticDir.canonicalFile
    val canonicalCandidate = try {
        candidate.canonicalFile
    } catch (invalidPath: IOException) {
        return null
    }
    val insideRoot = canonicalCandidate == canonicalRoot ||
        canonicalCandidate.path.startsWith(canonicalRoot.path + File.separator)
    return canonicalCandidate.takeIf { insideRoot && it.isFile }
}

/**
 * Finds the folder with the Angular build next to the running jar
 * (`<install root>/static`, issue #38, D35 layout). It gives null when
 * the jar has no such sibling folder, for example a run through Gradle
 * in dev mode. The dev-mode UI comes from `ng serve` on its own port,
 * not from this folder (D27).
 */
fun defaultStaticDir(): File? {
    val codeSourceUrl = FrontendLocation::class.java.protectionDomain?.codeSource?.location ?: return null
    val codeSourceFile = try {
        File(codeSourceUrl.toURI())
    } catch (invalidUri: URISyntaxException) {
        return null
    }
    if (!codeSourceFile.isFile) return null
    val installRoot = codeSourceFile.parentFile?.parentFile ?: return null
    val staticDir = File(installRoot, "static")
    return staticDir.takeIf { it.isDirectory }
}

// A marker class for its own class-loader location only. In the
// distribution, the jar with this class sits at
// "<install root>/lib/<name>.jar".
private class FrontendLocation
