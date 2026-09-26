// The consumer smoke project for Spring Boot 4.1 with Gradle (issue #68).
// The app proves that the Octometer kit resolves and starts, on the
// newest Spring Boot line.
//
// The exact version 4.1.1 is the newest 4.1.x release. Confirmed on
// 2026-09-26 with the Maven Central index (maven-metadata.xml of
// org.springframework.boot:spring-boot-starter-parent): the versions
// 4.1.0-M1 to 4.1.0-RC1, then 4.1.0, then 4.1.1; 4.2.0-M1 and 4.2.0-M2
// are milestone builds of the next line, not a 4.1.x release.
plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.octometer.smoke"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    // The kit comes from mavenLocal(), after "./gradlew publishToMavenLocal"
    // at the repository root (issue #68, decision 2).
    mavenLocal()
}

// The kit dependency (design decision D25, issue #37). Its group id and
// its artifact id come from the output of "./gradlew publishToMavenLocal"
// on 2026-09-26: com.github.xamcross.octometer:octometer-kit-mongo:0.1.0.
// The artifact octometer-kit-core comes with it as a transitive
// dependency of octometer-kit-mongo and of octometer-kit-spring.
//
// This project skips octometer-kit-ktor (issue #68, decision 1). A Ktor
// route needs a running Ktor server. It cannot sit inside a Spring MVC
// app, so this project takes jvm-core and jvm-mongo only, and now
// jvm-spring too (issue #69, the maintainer's decision 3): this app
// mounts IngestController and sends it one real request, with no
// Spring Security.
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("com.github.xamcross.octometer:octometer-kit-mongo:0.1.0")
    implementation("com.github.xamcross.octometer:octometer-kit-spring:0.1.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Spring Boot 4.1 moves `@AutoConfigureMockMvc` out of
    // spring-boot-test-autoconfigure into this module, at the new
    // package org.springframework.boot.webmvc.test.autoconfigure
    // (confirmed on 2026-09-26 by listing each jar's classes:
    // spring-boot-test-autoconfigure 4.1.1 holds only its jdbc and json
    // test slices, with no MockMvc class; spring-boot-webmvc-test 4.1.1
    // holds AutoConfigureMockMvc at that new package).
    testImplementation("org.springframework.boot:spring-boot-webmvc-test:4.1.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
