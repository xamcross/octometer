// The ingest core of the kit. Java 21, no run-time dependency.
// Issue #26 adds the store interface. Issue #33 adds the rate limit.
// Issue #37 adds the maven-publish plugin. JitPack serves a module of a
// multi-module Gradle build under the group "com.github.<user>.<repo>"
// (confirmed on docs.jitpack.io, page "Guide to publishing libraries",
// section "Multi-module projects", on 2026-09-22).
import org.gradle.api.tasks.PathSensitivity

plugins {
    `java-library`
    `maven-publish`
}

group = "com.github.xamcross.octometer"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// JUnit Jupiter 6.1.3: confirmed on Maven Central on 2026-09-21
// (repo1.maven.org/maven2/org/junit/jupiter/junit-jupiter/maven-metadata.xml,
// <release>6.1.3</release>). monitor/backend has no JUnit Jupiter entry yet,
// thus this module states its own version.
// Follow-up: move this version to gradle/libs.versions.toml after issue #4 merges.
dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
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
