// The Ktor monitor server.
import org.gradle.jvm.application.tasks.CreateStartScripts

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

group = "octometer"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.auto.head.response)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.typesafe.config)
    implementation(libs.sqlite.jdbc)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.logback.classic)
}

application {
    mainClass.set("octometer.monitor.ApplicationKt")
}

tasks.test {
    useJUnitPlatform()
}

// The dev mode of D2 (docs/superpowers/specs/2026-09-21-octometer-design.md).
// The environment variable is one level below the argument in the
// precedence of octometer.monitor.config.loadConfig. An explicit
// -P:octometer.mode argument on `--args` still wins over this default,
// and `--args` cannot erase the dev mode this way.
tasks.named<JavaExec>("run") {
    environment("OCTOMETER_MODE", "dev")
}

// The version value must sit inside the task block. A value outside it
// becomes a field of the build script, and the script object cannot
// serialize for the configuration cache (Task.project at execution time).
tasks.processResources {
    val appVersion = project.version.toString()
    inputs.property("appVersion", appVersion)
    filesMatching("version.properties") {
        expand("version" to appVersion)
    }
}

// Issue #38, step 1: the Angular build of monitor/frontend, for the
// static folder of the distribution (D35 layout, a sibling of "lib").
// "npm ci" needs the exact lock file, thus a change to it, or to
// package.json, reruns the install. "npm run build" needs a fresh
// install and a source change.
val frontendDir = layout.projectDirectory.dir("../frontend")
private val isWindows = org.gradle.internal.os.OperatingSystem.current().isWindows
private val npmCommand = if (isWindows) "npm.cmd" else "npm"

val npmInstall = tasks.register<Exec>("npmInstall") {
    group = "frontend"
    description = "Installs the exact npm dependencies of monitor/frontend."
    workingDir = frontendDir.asFile
    // Correction round 1 of issue #38 (MINOR 2, release review): the
    // earlier comment named a broken node-gyp file as the reason for
    // "--ignore-scripts". That file exists on this machine, and a plain
    // "npm ci" also works here. npm 11 skips an install script by
    // default. The five packages of package-lock.json with an install
    // script (@parcel/watcher, esbuild, fsevents, lmdb,
    // msgpackr-extract) each read a prebuilt binary of their own
    // platform at run time, so the script adds nothing on this build.
    // "--ignore-scripts" stays as a deliberate security choice: a
    // dependency update cannot add a life cycle script that this task
    // then runs.
    commandLine(npmCommand, "ci", "--ignore-scripts")
    inputs.file(frontendDir.file("package-lock.json"))
    inputs.file(frontendDir.file("package.json"))
    // Correction round 1 (MINOR 3, security review): the whole
    // node_modules tree is not a Gradle output. npm writes and reads
    // many files there with no build meaning, so Gradle fingerprinted
    // the whole tree on each build. The lock file that npm itself
    // writes at the end of a successful install marks the task done.
    outputs.file(frontendDir.file("node_modules/.package-lock.json"))
}

// Correction round 1 (MINOR 4, release review): the Angular build wrote
// into monitor/frontend/dist, a folder inside the source tree.
// ".gitignore" excludes it, but "./gradlew clean" left it behind. The
// output now sits under build/, alongside every other Gradle output.
val frontendBuildDir = layout.buildDirectory.dir("frontend-dist")

val buildFrontend = tasks.register<Exec>("buildFrontend") {
    group = "frontend"
    description = "Builds the Angular app of monitor/frontend for the distribution."
    dependsOn(npmInstall)
    workingDir = frontendDir.asFile
    commandLine(
        npmCommand,
        "run",
        "build",
        "--",
        "--output-path",
        frontendBuildDir.get().asFile.absolutePath,
    )
    inputs.dir(frontendDir.dir("src"))
    inputs.dir(frontendDir.dir("public"))
    inputs.file(frontendDir.file("angular.json"))
    inputs.file(frontendDir.file("tsconfig.app.json"))
    inputs.file(frontendDir.file("tsconfig.json"))
    // The Angular application builder nests its output under a "browser"
    // folder of the given output path, even with no server build.
    outputs.dir(frontendBuildDir.map { it.dir("browser") })
}

// Issue #38, step 2: distZip and installDist package the Angular build
// as the "static" folder next to "lib".
distributions {
    main {
        contents {
            from(buildFrontend) {
                into("static")
            }
        }
    }
}

// Issue #38, silent rule 3 (the task brief): "build" and "test" must
// still pass on a machine with no Node, when nobody asks for the
// frontend task. The application plugin wires distTar and distZip into
// "assemble" by default, and each one now needs buildFrontend (thus
// Node), because of the distributions block above. "distZip" and
// "installDist" stay directly runnable by name, each with the Angular
// build.
//
// Correction round 1 (MINOR 6, security review): the review asked for
// "dependsOn.remove(tasks.named(...))" in place of setDependsOn, so a
// later plugin that adds its own artifact to "assemble" keeps it. I
// tried that exact call and it threw "Removing a task dependency from a
// task instance is not supported" (Gradle 9.7.1). I then read
// dependsOn.toList() at this point: the distribution plugin wires
// distTar and distZip into "assemble" as one merged, opaque
// TaskDependency object, not as separate entries, so no public Gradle
// API can pull the two of them back out of it. setDependsOn(jar) stays,
// now with this record of why. A later plugin that adds to "assemble"
// needs a fresh look at this line; nothing here can protect it from
// this override today.
tasks.named("assemble") {
    setDependsOn(listOf(tasks.named("jar")))
}
// README.md of this module names ":monitor:backend:distZip" as the
// command that builds the release zip, because plain "assemble" and
// "build" no longer produce it.

// Issue #38, step 3 (D35): the wildcard class path stops the Windows
// command line from growing past its length limit as the dependency
// count grows.
tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        val classPathLine = Regex("""(?m)^set CLASSPATH=.*$""")
        // The replace(Regex, String) overload reads a backslash in the
        // replacement text as an escape character, so it drops each one.
        // The lambda overload below takes the return value as a literal
        // string instead.
        val original = windowsScript.readText()
        val rewritten = original.replace(classPathLine) { "set CLASSPATH=%APP_HOME%\\lib\\*" }
        // Correction round 1 (MAJOR 4, release review): a Gradle version
        // that renames or reformats the template line must not pass
        // silently, with the long explicit class path left in place.
        // This check fails the build loudly instead.
        check(rewritten != original) {
            "The Windows start script template holds no 'set CLASSPATH=' line to replace."
        }
        windowsScript.writeText(rewritten)
    }
}
