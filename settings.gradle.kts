// The root Gradle build of Octometer.
// The foojay resolver finds a JDK toolchain when the local machine does not have it.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "octometer"

include(":monitor:backend")
