// The Ktor adapter of the kit. Only this module uses Kotlin (design decision D17).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "octometer"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

// JUnit Jupiter 6.1.3: confirmed on Maven Central on 2026-09-21
// (repo1.maven.org/maven2/org/junit/jupiter/junit-jupiter/maven-metadata.xml,
// <release>6.1.3</release>), the same version as kit/jvm-core.
dependencies {
    api(project(":kit:jvm-core"))

    compileOnly(libs.ktor.server.core)
    compileOnly(libs.kotlinx.coroutines.core)

    testImplementation(libs.ktor.server.core)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
