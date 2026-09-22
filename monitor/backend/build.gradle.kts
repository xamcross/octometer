// The Ktor monitor server.
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
import org.gradle.jvm.application.tasks.CreateStartScripts
import java.io.IOException
import java.util.concurrent.TimeUnit

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

// The Testcontainers version of the container test of issue #16, the same
// version that kit/jvm-mongo confirms on Maven Central (see
// gradle/libs.versions.toml). The test also links kit/jvm-mongo, so it can
// write a test event with the store of the app side of the contract.
val testcontainersVersion = libs.versions.testcontainers.get()
val mongoDriverSyncVersion = libs.versions.mongodb.driver.sync.get()

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
    implementation(libs.mongodb.driver.kotlin.coroutine)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.logback.classic)
    // Issue #17: the virtual-time tests of the poll scheduler.
    testImplementation(libs.kotlinx.coroutines.test)

    // Issue #16: the container test of MongoAppReader needs the full
    // JUnit Jupiter engine and the Testcontainers MongoDB module.
    testImplementation(platform("org.testcontainers:testcontainers-bom:$testcontainersVersion"))
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-mongodb")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mongodb:mongodb-driver-sync:$mongoDriverSyncVersion")
    testImplementation(project(":kit:jvm-mongo"))
    testImplementation(project(":kit:jvm-core"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("octometer.monitor.ApplicationKt")
}

tasks.test {
    useJUnitPlatform()
    // Correction round 2 of issue #38 (MAJOR, second security review):
    // FrontendCspTest and WindowsStartScriptTest read these two files
    // directly, with no Gradle input link to them. Gradle could then
    // restore a skipped, cached result even after a later build adds
    // the files. Both paths are now real task inputs, so a build that
    // adds either file always reruns this task.
    inputs.files(layout.buildDirectory.file("frontend-dist/browser/index.html"))
        .withPropertyName("frontendIndexHtml")
        .optional()
    inputs.files(layout.buildDirectory.file("install/backend/bin/backend.bat"))
        .withPropertyName("windowsStartScript")
        .optional()
}

// Issue #177, step 1. Each test listener below reads OCTOMETER_REQUIRE_DOCKER
// or OCTOMETER_REQUIRE_DIST from the environment, so a value change changes
// the task result. This input declaration tells the Gradle build cache
// about both values. Without it, a cached PASS from one value can hide a
// real failure of the other value.
tasks.withType<Test>().configureEach {
    inputs.property("octometerRequireDocker", providers.environmentVariable("OCTOMETER_REQUIRE_DOCKER").orElse(""))
    inputs.property("octometerRequireDist", providers.environmentVariable("OCTOMETER_REQUIRE_DIST").orElse(""))
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

// MAJOR 3, Kotlin review of pull request #160. A container test needs
// Docker (@Testcontainers(disabledWithoutDocker = true)); with no
// Docker, the whole class skips with no failure. The job "JVM modules"
// on Ubuntu always has Docker, so a skip there hides a real problem.
//
// The job "Monitor backend on Windows" has no Docker by design, and it
// still runs this same test task. A guard on the plain CI variable
// would fail that job too, because GitHub Actions sets CI=true on
// every job. The guard below instead reads
// OCTOMETER_REQUIRE_DOCKER, a variable that .github/workflows/ci.yml
// sets on the Ubuntu job only (see kit/jvm-mongo/build.gradle.kts for
// the same pattern, keyed on CI there, because its test task never
// runs on the Windows job).
//
// Correction: monitor/backend also holds two tests that skip by design
// on Ubuntu, through Assumptions.assumeTrue for a Windows-only path
// (SecretStoreTest, DatabaseBackupTest). A guard on the whole task, as
// kit/jvm-mongo's guard reads, would fail on that skip too, with no
// tie to Docker. The listener below checks only a class suite whose
// name ends with "ContainerTest", the naming of each Testcontainers
// class of this module.
tasks.withType<Test>().configureEach {
    if (System.getenv("OCTOMETER_REQUIRE_DOCKER") == "true") {
        addTestListener(object : TestListener {
            override fun beforeSuite(suite: TestDescriptor) {}

            override fun beforeTest(testDescriptor: TestDescriptor) {}

            override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}

            override fun afterSuite(suite: TestDescriptor, result: TestResult) {
                val className = suite.className
                if (className != null && className.endsWith("ContainerTest") && result.skippedTestCount > 0) {
                    throw GradleException(
                        "The class \"$className\" of monitor:backend skipped " +
                            "${result.skippedTestCount} test(s) on CI. A container " +
                            "test needs Docker. Add Docker to this job, or find why " +
                            "it is absent."
                    )
                }
            }
        })
    }
}

// Correction round 2 of issue #38 (MAJOR, release review): FrontendCspTest
// and WindowsStartScriptTest each skip through Assumptions.assumeTrue until
// a build artifact exists (the frontend build, or installDist). The job
// "Monitor backend on Windows" now runs the test task after installDist
// (ci.yml), so a skip there hides a real problem. The guard below reads
// OCTOMETER_REQUIRE_DIST, a variable that ci.yml sets on that test step
// only, following the pattern of the OCTOMETER_REQUIRE_DOCKER guard above.
tasks.withType<Test>().configureEach {
    if (System.getenv("OCTOMETER_REQUIRE_DIST") == "true") {
        addTestListener(object : TestListener {
            override fun beforeSuite(suite: TestDescriptor) {}

            override fun beforeTest(testDescriptor: TestDescriptor) {}

            override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}

            override fun afterSuite(suite: TestDescriptor, result: TestResult) {
                val className = suite.className
                val distTestClassNames = setOf(
                    "octometer.monitor.frontend.FrontendCspTest",
                    "octometer.monitor.windows.WindowsStartScriptTest",
                )
                if (className !in distTestClassNames) return
                // Correction round 2 (CI proof): the default Gradle
                // console prints no per-class line on a pass. This one
                // line lets the CI log itself prove the skip count of
                // each dist guard test, for the pull request comment.
                logger.lifecycle(
                    "$className: tests=${result.testCount} " +
                        "failures=${result.failedTestCount} " +
                        "skipped=${result.skippedTestCount}"
                )
                if (result.skippedTestCount > 0) {
                    throw GradleException(
                        "The class \"$className\" of monitor:backend skipped " +
                            "${result.skippedTestCount} test(s) on CI. Run " +
                            "installDist before this test task."
                    )
                }
            }
        })
    }
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
    // default. Five packages of package-lock.json hold an install
    // script: @parcel/watcher, esbuild, fsevents, lmdb, and
    // msgpackr-extract. Each one reads a prebuilt binary of its own
    // platform at run time. The script adds nothing on this build.
    // "--ignore-scripts" stays as a deliberate security choice: a
    // dependency update cannot add a life cycle script that this task
    // then runs.
    commandLine(npmCommand, "ci", "--ignore-scripts")
    inputs.file(frontendDir.file("package-lock.json"))
    inputs.file(frontendDir.file("package.json"))
    // Correction round 1 (MINOR 3, security review): the whole
    // node_modules tree is not a Gradle output. npm writes and reads
    // many files there with no meaning for the build, so Gradle
    // fingerprinted the whole tree on each build. The lock file that
    // npm itself writes at the end of a successful install marks the
    // task done.
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
// dependsOn.toList() at this point. The distribution plugin wires
// distTar and distZip into "assemble" as one merged, opaque
// TaskDependency object. It holds no separate entries, so no public
// Gradle API can pull the two back out of it. setDependsOn(jar) stays,
// now with this record of why. A later plugin that adds to "assemble"
// needs a fresh look at this line; nothing here can protect it from
// this override today.
//
// Correction round 2 (MINOR 3, second security review): no public API
// can remove one entry from the merged TaskDependency, but this build
// can still read it before the override. The check below fails the
// build when assemble later gets a different dependency set, from a
// new Gradle version or a new plugin.
tasks.named("assemble") {
    val dependencyNames = taskDependencies.getDependencies(this).map { it.name }.toSet()
    check(dependencyNames == setOf("jar", "distTar", "distZip")) {
        "assemble now depends on $dependencyNames. Read this override before it drops a new artifact."
    }
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
