// The Gradle module of the npm build of kit/tracker (design decision
// D53, issue #298; the brief review of 2026-10-01, B4). This file owns
// the task "npmBuild". Before this module, each of tools/demo-app and
// monitor/backend ran its own "npm ci" and "npm run build" of
// kit/tracker, from its own build script. Two such tasks can run at
// the same time and write the same "dist" folder together (a real risk
// of a parallel Gradle build, the correction's own words). Every other
// module now depends on ":kit:tracker:npmBuild" instead, so the npm
// build of this package runs at most once per Gradle invocation.
//
// This module holds no Kotlin and no Java code, thus no JVM plugin.
import org.gradle.internal.os.OperatingSystem

val npmExecutable: String = if (OperatingSystem.current().isWindows) "npm.cmd" else "npm"

// The same PATH probe as tools/demo-app/build.gradle.kts (issue #14,
// Ktor review MAJOR 3): a build with no Node 24 and no npm on the PATH
// fails fast, with a message that names the setup document.
fun npmIsOnPath(): Boolean {
    val pathValue = System.getenv("PATH") ?: return false
    return pathValue.split(File.pathSeparator).any { directory ->
        directory.isNotBlank() && File(directory, npmExecutable).isFile
    }
}

val npmNotOnPathMessage =
    "The tracker build needs Node 24 and npm on the PATH. Install Node 24. Read kit/tracker/README.md for the setup steps."

// One shell line runs "npm ci" and then "npm run build", so one task
// owns both steps and thus the whole "dist" folder (the correction's
// instruction: "one task npmBuild"). The shell form differs by
// platform, the same rule as the Windows start script override of
// monitor/backend/build.gradle.kts.
val npmBuildCommand = "$npmExecutable ci --ignore-scripts && $npmExecutable run build"

/**
 * Installs the npm dependencies of kit/tracker, with a clean,
 * reproducible install, then builds both outputs: the ESM package of
 * `dist/index.js` and the IIFE bundle of `dist/tracker.iife.js` (design
 * decision D53). tools/demo-app and monitor/backend each depend on
 * this one task, never on an npm run of their own (the brief review of
 * 2026-10-01, B4): two Gradle tasks must never own this module's own
 * `dist` folder at the same time.
 */
val npmBuild = tasks.register<Exec>("npmBuild") {
    group = "build"
    description = "Installs the tracker dependencies and builds both outputs (npm ci, then npm run build)."
    workingDir = projectDir
    doFirst {
        if (!npmIsOnPath()) {
            throw GradleException(npmNotOnPathMessage)
        }
    }
    if (OperatingSystem.current().isWindows) {
        commandLine("cmd", "/c", npmBuildCommand)
    } else {
        commandLine("sh", "-c", npmBuildCommand)
    }
    inputs.dir(projectDir.resolve("src"))
    inputs.files(
        projectDir.resolve("package.json"),
        projectDir.resolve("package-lock.json"),
        projectDir.resolve("tsconfig.json"),
        projectDir.resolve("tsconfig.build.json"),
        projectDir.resolve("tsconfig.build.types.json"),
        projectDir.resolve("scripts/print-iife-size.mjs"),
    )
    outputs.dir(projectDir.resolve("dist"))
}

tasks.register("build") {
    group = "build"
    description = "An alias of npmBuild, for a plain 'build' of this module (this module has no JVM task)."
    dependsOn(npmBuild)
}
