// The demo app of Octometer (issue #14). It writes tracker clicks and
// synthetic clicks into a local MongoDB. It uses the ingest route of
// kit/jvm-ktor and the MongoDB store of kit/jvm-mongo, through a project
// dependency, not through JitPack.
//
// The static page loads the built tracker of kit/tracker. The Gradle
// module ":kit:tracker" (issue #298) runs "npm ci" and "npm run build"
// of kit/tracker, through its own task "npmBuild"; the one task below
// copies the built files into the resources of this module. The build
// needs Node 24 and npm on the PATH; read tools/demo-app/README.md for
// the setup steps (Ktor review MAJOR 3).
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport
import java.io.IOException
import java.util.concurrent.TimeUnit

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    jacoco
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

// Issue #176, decision 1 (correction of the brief review, 2026-09-27).
// See kit/jvm-core/build.gradle.kts for the note on the missing
// dependency of jacocoTestReport on test, and on the real command.
jacoco {
    toolVersion = libs.versions.jacoco.get()
}

val coverageOn = providers.gradleProperty("coverage").map { it == "true" }.getOrElse(false)

tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        isEnabled = coverageOn
    }
}

tasks.named<JacocoReport>("jacocoTestReport") {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    outputs.cacheIf { false }
}

// Issue #177, step 3 (release review MAJOR 5). A container test skips
// with no failure when Docker is absent. The build cache must not
// store that skipped run as a cached pass. This probe runs at
// execution time, not at configuration time, so each build reads the
// Docker state of that run.
//
// This module has no Testcontainers class on the build script
// classpath. Only the test classpath holds it. The probe below runs
// a plain "docker info" process instead, with a 10 second timeout. A
// module with Testcontainers on the build classpath can call
// DockerClientFactory.instance().isDockerAvailable() instead.
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

val trackerDir = rootProject.layout.projectDirectory.dir("kit/tracker")

// Issue #298 (the brief review of 2026-10-01, B4): the npm build of
// kit/tracker now lives in its own Gradle module, ":kit:tracker", with
// the one task "npmBuild". Two Gradle tasks must never own
// kit/tracker/dist at the same time; this module depends on that
// shared task now, in place of the npm Exec tasks this file held
// before (issue #14, step 4).

// This task copies each built JavaScript file of the tracker, and each
// source map, into the resources of this module, at "static/tracker/".
// The route of Application.kt serves each file of that folder under the
// URL path "/tracker/". A source map lets the browser devtools show the
// original tracker source (Ktor review MINOR 5). This task copies no
// ".d.ts" file, because the browser needs none.
val copyTrackerDist = tasks.register<Copy>("copyTrackerDist") {
    description = "Copies the built tracker into the static resources of the demo app (issue #14, step 4)."
    dependsOn(":kit:tracker:npmBuild")
    from(trackerDir.dir("dist")) {
        include("*.js", "*.js.map")
    }
    into(layout.buildDirectory.dir("resources/main/static/tracker"))
}

tasks.named("processResources") {
    dependsOn(copyTrackerDist)
}
