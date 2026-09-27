// The root build of Octometer. The Kotlin plugin loads one time here.
// Each Kotlin module (monitor/backend, kit/jvm-ktor) applies it again with
// the same alias; that keeps one class loader for the plugin.
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

// Issue #176, decision 1 (correction of the brief review, 2026-09-27).
// This project applies the Kotlin plugin with "apply false" only, so the
// "jacoco-report-aggregation" plugin does not fit here. The task type
// "JacocoReport" ships inside the Gradle distribution itself, so this
// root build reads it directly, with no "jacoco" plugin applied at this
// level.
//
// The six JVM modules of design decision D17 and of `settings.gradle.kts`.
val jacocoModulePaths = listOf(
    ":monitor:backend",
    ":kit:jvm-core",
    ":kit:jvm-ktor",
    ":kit:jvm-mongo",
    ":kit:jvm-spring",
    ":tools:demo-app",
)

// The "jacoco" plugin, applied in each of the six modules, gives every
// task of type JacocoReport of that module a default "jacocoClasspath"
// (the report generator library, "org.jacoco:org.jacoco.ant"). This root
// project applies no such plugin (the comment above), so its own
// JacocoReport task needs this classpath by hand. Confirmed on
// 2026-09-27: a first run with no such value failed with "Type
// 'org.gradle.testing.jacoco.tasks.JacocoReport' property
// 'jacocoClasspath' doesn't have a configured value".
val jacocoAntClasspath: Configuration = configurations.create("jacocoAntClasspath") {
    isCanBeConsumed = false
}

dependencies {
    jacocoAntClasspath("org.jacoco:org.jacoco.ant:${libs.versions.jacoco.get()}")
}

tasks.register<JacocoReport>("jacocoAggregate") {
    group = "verification"
    description = "Merges the JaCoCo test data of the six JVM modules into one XML report and one HTML report."
    val moduleProjects = jacocoModulePaths.map { project(it) }
    dependsOn(jacocoModulePaths.map { "$it:test" })
    jacocoClasspath = jacocoAntClasspath
    executionData.setFrom(moduleProjects.map { it.layout.buildDirectory.file("jacoco/test.exec") })
    classDirectories.setFrom(
        moduleProjects.map {
            it.fileTree(it.layout.buildDirectory.dir("classes/java/main")) +
                it.fileTree(it.layout.buildDirectory.dir("classes/kotlin/main"))
        },
    )
    sourceDirectories.setFrom(
        moduleProjects.flatMap {
            listOf(
                it.layout.projectDirectory.dir("src/main/kotlin"),
                it.layout.projectDirectory.dir("src/main/java"),
            )
        },
    )
    reports {
        xml.required.set(true)
        html.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/aggregate/jacocoAggregate.xml"))
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/aggregate/html"))
    }
    // The owner's changed-only-tests rule of 2026-09-22: a report task
    // that merges data of other tasks stores no cache entry of its own.
    outputs.cacheIf { false }
}
