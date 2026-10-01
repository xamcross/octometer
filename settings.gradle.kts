// The root Gradle build of Octometer.
// The foojay resolver finds a JDK toolchain when the local machine does not have it.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "octometer"

// A shared repository for each module. Each JVM module is here now
// (`monitor/backend`, `kit/jvm-core`, `kit/jvm-ktor`, `kit/jvm-mongo`,
// `kit/jvm-spring`, `tools/demo-app`). The root `build.gradle.kts` centers
// the Kotlin plugin. A later issue can add a convention plugin for the
// toolchain and the test setup.
//
// `kit:tracker` (issue #298, the brief review of 2026-10-01, B4) holds
// no JVM code. It wraps the npm build of `kit/tracker` in one Gradle
// task, `npmBuild`, so no two Gradle modules run that npm build at the
// same time.
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

include(":monitor:backend")
include(":kit:jvm-core")
include(":kit:jvm-ktor")
include(":kit:jvm-mongo")
include(":kit:jvm-spring")
include(":kit:tracker")
include(":tools:demo-app")
