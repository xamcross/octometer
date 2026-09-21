// The ingest core of the kit. Java 21, no run-time dependency.
// Issue #26 adds the store interface. Issue #33 adds the rate limit.
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
dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
