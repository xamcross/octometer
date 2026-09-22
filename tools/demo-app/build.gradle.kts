// The demo app of Octometer (issue #14). It writes tracker clicks and
// synthetic clicks into a local MongoDB. It uses the ingest route of
// kit/jvm-ktor and the MongoDB store of kit/jvm-mongo, through a project
// dependency, not through JitPack.
//
// The static page loads the built tracker of kit/tracker. This build runs
// npm ci and npm run build inside kit/tracker, then copies the built
// files into the resources of this module (see the three tasks below).
// Node 24 and npm are already installed on the build machine, so this
// build calls them directly; it needs no portable Node copy.
import org.gradle.internal.os.OperatingSystem

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
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
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

val npmExecutable: String = if (OperatingSystem.current().isWindows) "npm.cmd" else "npm"
val trackerDir = rootProject.layout.projectDirectory.dir("kit/tracker")

// Step 4 of issue #14: the static page loads the built tracker. This
// task installs the tracker dependencies with a clean, reproducible
// install. It skips each npm lifecycle script, because the tracker
// needs none at install time.
val npmCiTracker = tasks.register<Exec>("npmCiTracker") {
    description = "Installs the npm dependencies of kit/tracker (issue #14, step 4)."
    workingDir = trackerDir.asFile
    commandLine(npmExecutable, "ci", "--ignore-scripts")
    inputs.file(trackerDir.file("package-lock.json"))
    inputs.file(trackerDir.file("package.json"))
    outputs.dir(trackerDir.dir("node_modules"))
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

// This task copies each built JavaScript file of the tracker into the
// resources of this module, at "static/tracker/". The route of
// Application.kt serves each file of that folder under the URL path
// "/tracker/". This task copies no ".d.ts" file and no ".map" file,
// because the browser needs neither one.
val copyTrackerDist = tasks.register<Copy>("copyTrackerDist") {
    description = "Copies the built tracker into the static resources of the demo app (issue #14, step 4)."
    dependsOn(buildTracker)
    from(trackerDir.dir("dist")) {
        include("*.js")
    }
    into(layout.buildDirectory.dir("resources/main/static/tracker"))
}

tasks.named("processResources") {
    dependsOn(copyTrackerDist)
}
