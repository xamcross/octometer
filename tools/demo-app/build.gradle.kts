// The demo app of Octometer (issue #14). It writes tracker clicks and
// synthetic clicks into a local MongoDB. It uses the ingest route of
// kit/jvm-ktor and the MongoDB store of kit/jvm-mongo, through a project
// dependency, not through JitPack.
//
// The static page loads the built tracker of kit/tracker. This build runs
// npm ci and npm run build inside kit/tracker, then copies the built
// files into the resources of this module (see the three tasks below).
// The build needs Node 24 and npm on the PATH; read
// tools/demo-app/README.md for the setup steps (Ktor review MAJOR 3).
// The task npmCiTracker checks the PATH first, and it fails with a clear
// message when npm is absent.
import org.gradle.internal.os.OperatingSystem
import java.io.IOException
import java.util.concurrent.TimeUnit

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

group = "octometer"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":kit:jvm-core"))
    implementation(project(":kit:jvm-ktor"))
    implementation(project(":kit:jvm-mongo"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.mongodb.driver.sync)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.logback.classic)
    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.mongodb.driver.sync)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-mongodb")
}

application {
    mainClass.set("octometer.demo.ApplicationKt")
}

tasks.test {
    useJUnitPlatform()
}

// Issue #177, step 3 (release review MAJOR 5). A container test skips
// with no failure when Docker is absent. The build cache must not
// store that skipped run as a cached pass. This probe runs at
// execution time, not at configuration time, so each build reads the
// Docker state of that run.
//
// This module has no Testcontainers class on the build script
// classpath (only on the test classpath), so the probe below starts
// a plain "docker info" process, with a 10 second timeout, instead
// of DockerClientFactory.instance().isDockerAvailable().
val dockerAvailable = providers.provider {
    try {
        val process = ProcessBuilder("docker", "info").redirectErrorStream(true).start()
        val finished = process.waitFor(10, TimeUnit.SECONDS)
        if (finished) process.exitValue() == 0 else { process.destroyForcibly(); false }
    } catch (error: IOException) {
        false
    }
}

tasks.withType<Test>().configureEach {
    outputs.doNotCacheIf("Docker is absent") { !dockerAvailable.get() }
}

val npmExecutable: String = if (OperatingSystem.current().isWindows) "npm.cmd" else "npm"
val trackerDir = rootProject.layout.projectDirectory.dir("kit/tracker")

// Ktor review MAJOR 3: checks the PATH for npmExecutable, the same
// executable name that the Exec tasks below run. A build with no match
// must fail fast, with a message that names Node 24, npm, and the setup
// document.
fun npmIsOnPath(): Boolean {
    val pathValue = System.getenv("PATH") ?: return false
    return pathValue.split(File.pathSeparator).any { directory ->
        directory.isNotBlank() && File(directory, npmExecutable).isFile
    }
}

val npmNotOnPathMessage =
    "The demo app build needs Node 24 and npm on the PATH. Install Node 24. Read tools/demo-app/README.md for the setup steps."

// Step 4 of issue #14: the static page loads the built tracker. This
// task installs the tracker dependencies with a clean, reproducible
// install. It skips each npm lifecycle script, because the tracker
// needs none at install time.
//
// The two Exec tasks of this file write into kit/tracker/node_modules
// and kit/tracker/dist, outside this module (Ktor review MINOR 6). Run
// no other build of kit/tracker at the same time as this module builds,
// or the two builds can write the same folder.
val npmCiTracker = tasks.register<Exec>("npmCiTracker") {
    description = "Installs the npm dependencies of kit/tracker (issue #14, step 4)."
    workingDir = trackerDir.asFile
    doFirst {
        if (!npmIsOnPath()) {
            throw GradleException(npmNotOnPathMessage)
        }
    }
    commandLine(npmExecutable, "ci", "--ignore-scripts")
    inputs.file(trackerDir.file("package-lock.json"))
    inputs.file(trackerDir.file("package.json"))
    // Security review MINOR 4: a file output, not a directory output, so
    // Gradle fingerprints one file instead of the whole node_modules tree.
    outputs.file(trackerDir.file("node_modules/.package-lock.json"))
}

// This task builds the tracker with its own npm script, so the demo app
// loads the same built file as an app that depends on the tracker
// package.
val buildTracker = tasks.register<Exec>("buildTracker") {
    description = "Builds kit/tracker, so the demo page can load the built tracker (issue #14, step 4)."
    dependsOn(npmCiTracker)
    workingDir = trackerDir.asFile
    commandLine(npmExecutable, "run", "build")
    inputs.dir(trackerDir.dir("src"))
    inputs.files(
        trackerDir.file("tsconfig.json"),
        trackerDir.file("tsconfig.build.json"),
        trackerDir.file("tsconfig.build.types.json"),
    )
    outputs.dir(trackerDir.dir("dist"))
}

// This task copies each built JavaScript file of the tracker, and each
// source map, into the resources of this module, at "static/tracker/".
// The route of Application.kt serves each file of that folder under the
// URL path "/tracker/". A source map lets the browser devtools show the
// original tracker source (Ktor review MINOR 5). This task copies no
// ".d.ts" file, because the browser needs none.
val copyTrackerDist = tasks.register<Copy>("copyTrackerDist") {
    description = "Copies the built tracker into the static resources of the demo app (issue #14, step 4)."
    dependsOn(buildTracker)
    from(trackerDir.dir("dist")) {
        include("*.js", "*.js.map")
    }
    into(layout.buildDirectory.dir("resources/main/static/tracker"))
}

tasks.named("processResources") {
    dependsOn(copyTrackerDist)
}
