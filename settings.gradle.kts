// The root Gradle build of Octometer.
// The foojay resolver finds a JDK toolchain when the local machine does not have it.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "octometer"

// A shared repository for each module. Each JVM module is here now
// (`monitor/backend`, `kit/jvm-core`, `kit/jvm-ktor`, `kit/jvm-mongo`,
// `tools/demo-app`). The root `build.gradle.kts` centers the Kotlin
// plugin. A later issue can add a convention plugin for the toolchain and
// the test setup.
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
include(":tools:demo-app")
