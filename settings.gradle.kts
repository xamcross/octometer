// The root Gradle build of Octometer.
// The foojay resolver finds a JDK toolchain when the local machine does not have it.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "octometer"

// A shared repository for each module. A later issue adds a convention plugin
// for the toolchain and the test setup, when the second JVM module arrives.
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

include(":monitor:backend")
include(":kit:jvm-core")
