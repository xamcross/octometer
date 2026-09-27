// The ingest core of the kit. Java 21, no run-time dependency.
// Issue #26 adds the store interface. Issue #33 adds the rate limit.
// Issue #37 adds the maven-publish plugin. JitPack serves a module of a
// multi-module Gradle build under the group "com.github.<user>.<repo>"
// (confirmed on docs.jitpack.io, page "Guide to publishing libraries",
// section "Multi-module projects", on 2026-09-22).
import org.gradle.api.tasks.PathSensitivity
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    `java-library`
    `maven-publish`
    jacoco
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

// JUnit Jupiter: gradle/libs.versions.toml holds the version (issue #138).
dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // A test reads contract/README.md and contract/examples/ (rules C39,
    // C40, C41, C42, issue #103). This input makes the task run again
    // after a contract edit, also with no source change in this module.
    inputs.file(rootProject.layout.projectDirectory.file("contract/README.md"))
        .withPropertyName("contractReadme")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.layout.projectDirectory.dir("contract/examples"))
        .withPropertyName("contractExamples")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// Issue #176, decision 1 (correction of the brief review, 2026-09-27).
// The jacoco plugin is applied in every JVM module, always. Only the
// agent stays off by default, so a plain test run keeps its present
// cache key and its present speed (the changed-only-tests pattern of
// the owner, 2026-09-22).
jacoco {
    toolVersion = libs.versions.jacoco.get()
}

val coverageOn = providers.gradleProperty("coverage").map { it == "true" }.getOrElse(false)

tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        isEnabled = coverageOn
    }
}

// Criterion 1 of issue #176 reads "./gradlew jacocoTestReport"; the
// jacoco plugin gives this task no dependency on "test" (confirmed
// against the Gradle manual, "The JaCoCo Plugin", 2026-09-27: "the
// jacocoTestReport task does not depend on the test task"). The real
// command is "./gradlew test jacocoTestReport -Pcoverage=true" (stated
// in the pull request and in the README).
tasks.named<JacocoReport>("jacocoTestReport") {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    outputs.cacheIf { false }
}

// The publication for JitPack (design decision D25, issue #37). The
// artifact id "octometer-kit-core" comes from D25.
//
// Note: the repository has no LICENSE file and no license field on
// GitHub (confirmed with `gh api repos/xamcross/octometer` on
// 2026-09-22). The POM thus states no license. The owner adds a
// license, and then a later change adds it to the POM.
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "octometer-kit-core"

            pom {
                name.set("Octometer Kit Core")
                description.set("The ingest core of the Octometer JVM kit. It stores an event and reads it back, with no run-time dependency.")
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
