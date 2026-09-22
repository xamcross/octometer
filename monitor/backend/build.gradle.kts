// The Ktor monitor server.
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult

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
