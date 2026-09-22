package octometer.monitor.frontend

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.request.path
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.io.File
import java.io.IOException
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import octometer.monitor.ErrorBody
import octometer.monitor.UNKNOWN_API_ROUTE_MESSAGE
import octometer.monitor.security.isApiPath

private const val INDEX_FILE_NAME = "index.html"
private const val INDEX_CACHE_CONTROL = "no-cache"
private const val HASHED_ASSET_CACHE_CONTROL = "public, max-age=31536000, immutable"

// The Angular production build (design decision D27, outputHashing "all")
// names a bundle file with a dash and exactly 8 base64url characters
// before the extension, for example "main-GES6WX3U.js". base64url holds
// "-" and "_" too, for example "chunk-C-Ty_1Re.js". A plain asset, for
// example "public/favicon.ico", keeps its own name and matches no such
// pattern.
//
// Correction round 1 (MAJOR 2, security review; MAJOR 1, release
// review): the earlier pattern used the class [0-9A-Za-z] and a length
// of 8 or more, so it missed a hash with "-" or "_", and it matched a
// plain file name such as "logo-something.png" by mistake.
private val HASHED_FILE_NAME = Regex("""^.+-[0-9A-Za-z_-]{8}\.[0-9a-z]+$""")

/**
 * Serves the Angular build of `monitor/frontend` (issue #38, D28). The
 * caller adds this route only when [staticDir] exists.
 *
 * A request below `/api/` gets the fixed 404 of the API, never a file
 * (correction round 1, MINOR 1, security review). A request for a real
 * file under [staticDir] gets that file. Each other request gets
 * `index.html`, because the Angular router reads the browser path, not
 * the server (D28, the path router).
 *
 * `index.html` goes out with `Cache-Control: no-cache`, so a new release
 * reaches the browser at once. A hashed file name marks an immutable
 * file, so it gets a cache time of one year.
 */
fun Route.staticFrontend(staticDir: File) {
    get("/{path...}") {
        if (isApiPath(call.request.path())) {
            call.respond(HttpStatusCode.NotFound, ErrorBody(UNKNOWN_API_ROUTE_MESSAGE))
            return@get
        }
        val segments = call.parameters.getAll("path").orEmpty()
        val fileToServe = resolveRequestedFile(staticDir, segments)
        if (fileToServe != null) {
            respondStaticFile(call, fileToServe)
        } else {
            respondIndexOrMissing(call, staticDir)
        }
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

// Correction round 1 (MINOR 3, security review; MINOR 5, release
// review): a missing index.html must give the fixed 404 of the API, not
// the default 500 of respondFile on a file that does not exist. The
// start warning of Application.kt names the cause once, at the start.
private suspend fun respondIndexOrMissing(call: ApplicationCall, staticDir: File) {
    val indexFile = File(staticDir, INDEX_FILE_NAME)
    if (indexFile.isFile) {
        respondStaticFile(call, indexFile)
    } else {
        call.respond(HttpStatusCode.NotFound, ErrorBody(UNKNOWN_API_ROUTE_MESSAGE))
    }
}

// Turns the browser path into a real file under staticDir, or gives null
// when no such file exists, for example the Angular route "/apps".
//
// Correction round 1 (MAJOR 1, security review): File.getCanonicalFile
// resolves a "." segment and a ".." segment, but it does not resolve a
// Windows reparse point (a junction, a symbolic link). A link inside
// staticDir then let a request read a file outside it. Path.toRealPath
// resolves a reparse point too, so a segment such as ".." cannot leave
// staticDir, and neither can a link.
private fun resolveRequestedFile(staticDir: File, segments: List<String>): File? {
    if (segments.isEmpty() || segments.all { it.isEmpty() }) return null
    val candidate = segments.fold(staticDir) { dir, segment -> File(dir, segment) }
    val realRoot = try {
        staticDir.toPath().toRealPath()
    } catch (missing: IOException) {
        return null
    }
    val realCandidate = try {
        candidate.toPath().toRealPath()
    } catch (missing: IOException) {
        return null
    } catch (badName: InvalidPathException) {
        return null
    }
    return realCandidate.takeIf { it.isInside(realRoot) && Files.isRegularFile(it) }?.toFile()
}

// Path.startsWith compares whole name elements, so it needs no separator
// text, and it also matches the root path itself.
private fun Path.isInside(root: Path): Boolean = this.startsWith(root)

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
    return staticDirBesideJar(codeSourceFile)
}

// Split out of defaultStaticDir so a test can give its own codeSourceFile
// (correction round 1, corrections item 3): a real jar in a test folder
// proves the sibling-folder rule, with no need to package a real jar.
internal fun staticDirBesideJar(codeSourceFile: File): File? {
    if (!codeSourceFile.isFile) return null
    val installRoot = codeSourceFile.parentFile?.parentFile ?: return null
    val staticDir = File(installRoot, "static")
    return staticDir.takeIf { it.isDirectory }
}

// A marker class for its own class-loader location only. In the
// distribution, the jar with this class sits at
// "<install root>/lib/<name>.jar".
private class FrontendLocation
