// The MongoDB event store of the kit (design decision D22, issue #11).
// Java 21, no Kotlin library (design decision D17). The MongoDB driver is
// compileOnly: the app gives the MongoDatabase, and the kit never creates a
// client.
//
// org.mongodb:mongodb-driver-sync: the compile version is 5.0.1, the
// version of the Spring Boot 3.3 BOM (design decision D22). The test suite
// also runs against the newest 5.x version. Confirmed on Maven Central on
// 2026-09-21 (repo1.maven.org/maven2/org/mongodb/mongodb-driver-sync/
// maven-metadata.xml): the newest 5.x version is 5.12.0. Both versions sit
// in gradle/libs.versions.toml.
//
// JUnit Jupiter, Testcontainers, and Mockito are test-only dependencies.
// Each version sits in gradle/libs.versions.toml, confirmed on Maven
// Central on 2026-09-21.
//
// A container test needs Docker (@Testcontainers(disabledWithoutDocker =
// true)), and it skips with no failure when Docker is absent. The CI
// runner "JVM modules" always has Docker, so a skip there is a real
// problem, not an absent tool. The listener near the end of this file
// fails the build on a skip. It reads OCTOMETER_REQUIRE_DOCKER, the
// same key that monitor/backend reads, in place of the plain CI
// variable (issue #177, step 4).
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
import java.io.IOException
import java.util.concurrent.TimeUnit

plugins {
    `java-library`
    `maven-publish`
}

group = "com.github.xamcross.octometer"
version = libs.versions.octometer.kit.get()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    // A source jar for the JitPack publication (finding MINOR 2, PR #155).
    withSourcesJar()
}

val mongoDriverSyncVersion = libs.versions.mongodb.driver.sync.get()
val mongoDriverSyncNewestVersion = libs.versions.mongodb.sync.newest.get()

dependencies {
    api(project(":kit:jvm-core"))
    compileOnly(libs.mongodb.driver.sync)

    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.mongodb.driver.sync)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-mongodb")
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)
}

tasks.test {
    description = "Runs the test suite against mongodb-driver-sync $mongoDriverSyncVersion."
    useJUnitPlatform()
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

// Issue #177, step 4. This input declaration tells the Gradle build
// cache about OCTOMETER_REQUIRE_DOCKER, in the same form as
// monitor/backend/build.gradle.kts. Without it, a cached pass from one
// value can hide a real failure of the other value.
tasks.withType<Test>().configureEach {
    inputs.property("octometerRequireDocker", providers.environmentVariable("OCTOMETER_REQUIRE_DOCKER").orElse(""))
}

// A container test must run on CI. It must not silently skip. The
// listener fails the task when OCTOMETER_REQUIRE_DOCKER holds "true"
// and a test of that task skipped. A local run with no Docker, and
// with no OCTOMETER_REQUIRE_DOCKER, still skips a container test
// with no failure.
tasks.withType<Test>().configureEach {
    if (System.getenv("OCTOMETER_REQUIRE_DOCKER") == "true") {
        addTestListener(object : TestListener {
            override fun beforeSuite(suite: TestDescriptor) {}

            override fun beforeTest(testDescriptor: TestDescriptor) {}

            override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}

            override fun afterSuite(suite: TestDescriptor, result: TestResult) {
                if (suite.parent == null && result.skippedTestCount > 0) {
                    throw GradleException(
                        "The task \"${suite.name}\" of kit:jvm-mongo skipped " +
                            "${result.skippedTestCount} test(s) on CI. A container " +
                            "test needs Docker. Add Docker to this job, or find why " +
                            "it is absent."
                    )
                }
            }
        })
    }
}

// A second run of the same test suite, against the newest 5.x driver
// (issue #11, step 6). One configuration copies the test runtime
// classpath and drops mongodb-driver-sync. A second, plain configuration
// resolves only the newest version, with its own transitive files (for
// example bson). The task classpath joins the two.
val mongoSyncNewestClasspathWithoutDefaultDriver: Configuration =
    configurations.create("mongoSyncNewestClasspathWithoutDefaultDriver") {
        extendsFrom(configurations.getByName("testRuntimeClasspath"))
        isCanBeConsumed = false
        exclude(group = "org.mongodb", module = "mongodb-driver-sync")
    }

val mongoSyncNewestDriver: Configuration = configurations.create("mongoSyncNewestDriver") {
    isCanBeConsumed = false
}

dependencies {
    mongoSyncNewestDriver("org.mongodb:mongodb-driver-sync:$mongoDriverSyncNewestVersion")
}

val testMongoSyncNewest = tasks.register<Test>("testMongoSyncNewest") {
    description = "Runs the test suite again, against the newest 5.x mongodb-driver-sync."
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.main.get().output + sourceSets.test.get().output +
            mongoSyncNewestClasspathWithoutDefaultDriver + mongoSyncNewestDriver
}

tasks.check {
    dependsOn(testMongoSyncNewest)
}

// The publication for JitPack (design decision D25, issue #37). The
// artifact id "octometer-kit-mongo" comes from D25.
//
// The Gradle `java-library` component omits a compileOnly dependency
// from a published POM. This is the Gradle default, and this build
// keeps it: the published POM has no entry for
// org.mongodb:mongodb-driver-sync. The app gives the driver itself
// (see the file header of this module).
//
// See kit/jvm-core/build.gradle.kts for the note on the missing
// license.
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "octometer-kit-mongo"

            pom {
                name.set("Octometer Kit Mongo")
                description.set("The MongoDB event store of the Octometer JVM kit. The app gives the MongoDatabase and the mongodb-driver-sync jar.")
                url.set("https://github.com/xamcross/octometer")

                scm {
                    connection.set("scm:git:https://github.com/xamcross/octometer.git")
                    developerConnection.set("scm:git:https://github.com/xamcross/octometer.git")
                    url.set("https://github.com/xamcross/octometer")
                }
            }
        }
    }
}
