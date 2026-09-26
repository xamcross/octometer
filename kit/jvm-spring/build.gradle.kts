// The Spring MVC adapter of the kit (design decision D17, issue #69).
// Java 21, no Kotlin library. spring-web and the Jakarta Servlet API are
// compileOnly: the app gives Spring MVC, the same rule as kit/jvm-ktor
// gives Ktor and kit/jvm-mongo gives the MongoDB driver.
//
// org.springframework:spring-web and jakarta.servlet:jakarta.servlet-api:
// the compile versions are 6.2.1 and 6.0.0, the versions of the Spring
// Boot 3.4.1 dependency list. Confirmed on 2026-09-26 from the "v3.4.1"
// tag of spring-projects/spring-boot on GitHub (see the comment of
// gradle/libs.versions.toml for the exact source).

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
    // A source jar for the JitPack publication (the form of
    // kit/jvm-core, kit/jvm-ktor, and kit/jvm-mongo).
    withSourcesJar()
}

dependencies {
    api(project(":kit:jvm-core"))

    compileOnly(libs.spring.web)
    compileOnly(libs.jakarta.servlet.api)

    testImplementation(libs.spring.web)
    testImplementation(libs.spring.webmvc)
    testImplementation(libs.spring.test)
    testImplementation(libs.jakarta.servlet.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// The publication for JitPack (design decision D25, issue #37, issue
// #69). The artifact id "octometer-kit-spring" comes from D25 and from
// the goal line of issue #69.
//
// The Gradle `java-library` component omits a compileOnly dependency
// from a published POM. This is the Gradle default, and this build
// keeps it: the published POM has no entry for
// org.springframework:spring-web and no entry for
// jakarta.servlet:jakarta.servlet-api. The app gives Spring MVC itself
// (see the file header of this module).
//
// See kit/jvm-core/build.gradle.kts for the note on the missing
// license.
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "octometer-kit-spring"

            pom {
                name.set("Octometer Kit Spring")
                description.set("The Spring MVC adapter of the Octometer JVM kit. The app gives Spring MVC and the Jakarta Servlet API.")
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
