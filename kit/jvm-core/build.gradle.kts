// The ingest core of the kit. Java 21, no run-time dependency.
// Issue #26 adds the store interface. Issue #33 adds the rate limit.
import org.gradle.api.tasks.PathSensitivity

plugins {
    `java-library`
}

group = "octometer"
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
