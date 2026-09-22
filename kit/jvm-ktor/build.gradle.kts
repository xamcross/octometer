// The Ktor adapter of the kit. Only this module uses Kotlin (design decision D17).
// The root build.gradle.kts loads the Kotlin plugin one time; this module
// applies the same alias again.
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion as KotlinLanguageVersion

plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

group = "com.github.xamcross.octometer"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
    // Kotlin 2.0 is the lowest language and API version that this Kotlin
    // compiler (2.4.20) accepts without an error (confirmed on 2026-09-21:
    // version 1.6 gives the compiler error "Language version 1.6 is no
    // longer supported"; version 2.0 compiles with a deprecation warning
    // only). This lets an app on Kotlin 2.0, 2.1, or 2.2 read the class
    // files of this module (see README.md).
    compilerOptions {
        apiVersion.set(KotlinLanguageVersion.KOTLIN_2_0)
        languageVersion.set(KotlinLanguageVersion.KOTLIN_2_0)
    }
}

// JUnit Jupiter: gradle/libs.versions.toml holds the version, the same
// version as kit/jvm-core (issue #138).
dependencies {
    api(project(":kit:jvm-core"))

    compileOnly(libs.ktor.server.core)
    compileOnly(libs.kotlinx.coroutines.core)

    testImplementation(libs.ktor.server.core)
    testImplementation(libs.ktor.server.test.host)
    // A real embedded server, for one test that sends a malformed
    // Content-Type header over a raw socket. The default Ktor test client
    // parses the Content-Type header itself and rejects a malformed value
    // before it reaches the server.
    testImplementation(libs.ktor.server.netty)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.logback.classic)
    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// The publication for JitPack (design decision D25, issue #37). The
// artifact id "octometer-kit-ktor" comes from D25.
//
// The Gradle Kotlin/Java component omits a compileOnly dependency from
// a published POM. This is the Gradle default, and this build keeps
// it: the published POM has no entry for io.ktor:ktor-server-core or
// org.jetbrains.kotlinx:kotlinx-coroutines-core. The app gives Ktor
// itself (see the file header of this module).
//
// See kit/jvm-core/build.gradle.kts for the note on the missing
// license.
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "octometer-kit-ktor"

            pom {
                name.set("Octometer Kit Ktor")
                description.set("The Ktor adapter of the Octometer JVM kit. The app gives Ktor and the Kotlin coroutines library.")
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
