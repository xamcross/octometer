// The MongoDB event store of the kit (design decision D22, issue #11).
// Java 21, no Kotlin library (design decision D17). The MongoDB driver is
// compileOnly: the app gives the MongoDatabase, and the kit never creates a
// client.
//
// org.mongodb:mongodb-driver-sync: the compile version is 5.0.1, the
// version of the Spring Boot 3.3 BOM (design decision D22). The test suite
// also runs against the newest 5.x version. Confirmed on Maven Central on
// 2026-09-21 (repo1.maven.org/maven2/org/mongodb/mongodb-driver-sync/
// maven-metadata.xml): the newest 5.x version is 5.12.0. Both versions sit
// in gradle/libs.versions.toml.
//
// JUnit Jupiter, Testcontainers, and Mockito are test-only dependencies.
// monitor/backend has no shared entry for them yet, so this module states
// its own version, the same way kit/jvm-core states its JUnit version.
plugins {
    `java-library`
}

group = "octometer"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

val mongoDriverSyncVersion = libs.versions.mongodb.driver.sync.get()
val mongoDriverSyncNewestVersion = libs.versions.mongodb.sync.newest.get()
val testcontainersVersion = "2.0.5"
val mockitoVersion = "5.23.0"

dependencies {
    api(project(":kit:jvm-core"))
    compileOnly("org.mongodb:mongodb-driver-sync:$mongoDriverSyncVersion")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mongodb:mongodb-driver-sync:$mongoDriverSyncVersion")
    testImplementation(platform("org.testcontainers:testcontainers-bom:$testcontainersVersion"))
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-mongodb")
    testImplementation("org.mockito:mockito-core:$mockitoVersion")
    testImplementation("org.mockito:mockito-junit-jupiter:$mockitoVersion")
}

tasks.test {
    description = "Runs the test suite against mongodb-driver-sync $mongoDriverSyncVersion."
    useJUnitPlatform()
}

// Each test task prints its own pass, skip, and fail count. This lets a
// person read the CI log and confirm that a Testcontainers test really ran,
// and did not skip.
tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// A second run of the same test suite, against the newest 5.x driver
// (issue #11, step 6). One configuration copies the test runtime
// classpath and drops mongodb-driver-sync. A second, plain configuration
// resolves only the newest version, with its own transitive files (for
// example bson). The task classpath joins the two.
val mongoSyncNewestClasspathWithoutDefaultDriver: Configuration =
    configurations.create("mongoSyncNewestClasspathWithoutDefaultDriver") {
        extendsFrom(configurations.getByName("testRuntimeClasspath"))
        isCanBeConsumed = false
        exclude(group = "org.mongodb", module = "mongodb-driver-sync")
    }

val mongoSyncNewestDriver: Configuration = configurations.create("mongoSyncNewestDriver") {
    isCanBeConsumed = false
}

dependencies {
    mongoSyncNewestDriver("org.mongodb:mongodb-driver-sync:$mongoDriverSyncNewestVersion")
}

val testMongoSyncNewest = tasks.register<Test>("testMongoSyncNewest") {
    description = "Runs the test suite again, against the newest 5.x mongodb-driver-sync."
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.main.get().output + sourceSets.test.get().output +
            mongoSyncNewestClasspathWithoutDefaultDriver + mongoSyncNewestDriver
}

tasks.check {
    dependsOn(testMongoSyncNewest)
}
