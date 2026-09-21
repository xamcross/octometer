// The Ktor monitor server.
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

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit5)
}

application {
    mainClass.set("octometer.monitor.ApplicationKt")
}

tasks.test {
    useJUnitPlatform()
}

// The dev mode of D2 (docs/superpowers/specs/2026-09-21-octometer-design.md).
// Issue #4 adds the full config load. This line only sets the mode for `run`.
tasks.named<JavaExec>("run") {
    systemProperty("octometer.mode", "dev")
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
