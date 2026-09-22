// The Ktor monitor server.
import org.gradle.jvm.application.tasks.CreateStartScripts

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
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.typesafe.config)
    implementation(libs.sqlite.jdbc)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.logback.classic)
}

application {
    mainClass.set("octometer.monitor.ApplicationKt")
}

tasks.test {
    useJUnitPlatform()
}

// The dev mode of D2 (docs/superpowers/specs/2026-09-21-octometer-design.md).
// The environment variable is one level below the argument in the
// precedence of octometer.monitor.config.loadConfig. An explicit
// -P:octometer.mode argument on `--args` still wins over this default,
// and `--args` cannot erase the dev mode this way.
tasks.named<JavaExec>("run") {
    environment("OCTOMETER_MODE", "dev")
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

// Issue #38, step 1: the Angular build of monitor/frontend, for the
// static folder of the distribution (D35 layout, a sibling of "lib").
// "npm ci" needs the exact lock file, thus a change to it, or to
// package.json, reruns the install. "npm run build" needs a fresh
// install and a source change.
val frontendDir = layout.projectDirectory.dir("../frontend")
private val isWindows = org.gradle.internal.os.OperatingSystem.current().isWindows
private val npmCommand = if (isWindows) "npm.cmd" else "npm"

val npmInstall = tasks.register<Exec>("npmInstall") {
    group = "frontend"
    description = "Installs the exact npm dependencies of monitor/frontend."
    workingDir = frontendDir.asFile
    // "--ignore-scripts": the install of monitor/frontend needs no life
    // cycle script. "ng build" already works with each script skipped,
    // because each native package here ships a prebuilt file for its own
    // platform. This also skips a package script that needs a build
    // tool, for example node-gyp, that a bare JDK and Node install lacks.
    commandLine(npmCommand, "ci", "--ignore-scripts")
    inputs.file(frontendDir.file("package-lock.json"))
    inputs.file(frontendDir.file("package.json"))
    outputs.dir(frontendDir.dir("node_modules"))
}

val buildFrontend = tasks.register<Exec>("buildFrontend") {
    group = "frontend"
    description = "Builds the Angular app of monitor/frontend for the distribution."
    dependsOn(npmInstall)
    workingDir = frontendDir.asFile
    commandLine(npmCommand, "run", "build")
    inputs.dir(frontendDir.dir("src"))
    inputs.dir(frontendDir.dir("public"))
    inputs.file(frontendDir.file("angular.json"))
    inputs.file(frontendDir.file("tsconfig.app.json"))
    inputs.file(frontendDir.file("tsconfig.json"))
    outputs.dir(frontendDir.dir("dist/frontend/browser"))
}

// Issue #38, step 2: distZip and installDist package the Angular build
// as the "static" folder next to "lib".
distributions {
    main {
        contents {
            from(buildFrontend) {
                into("static")
            }
        }
    }
}

// Issue #38, silent rule 3 (the task brief): "build" and "test" must
// still pass on a machine with no Node, when nobody asks for the
// frontend task. The application plugin wires distTar and distZip into
// "assemble" by default, and each one now needs buildFrontend (thus
// Node), because of the distributions block above. This keeps
// "assemble" to the jar only, so the default "build" lifecycle, and the
// "jvm" CI job that runs it, stay Node-free. "distZip" and
// "installDist" stay directly runnable by name, each with the Angular
// build.
tasks.named("assemble") {
    setDependsOn(listOf(tasks.named("jar")))
}

// Issue #38, step 3 (D35): the wildcard class path stops the Windows
// command line from growing past its length limit as the dependency
// count grows.
tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        val classPathLine = Regex("""(?m)^set CLASSPATH=.*$""")
        // The replace(Regex, String) overload reads a backslash in the
        // replacement text as an escape character, so it drops each one.
        // The lambda overload below takes the return value as a literal
        // string instead.
        val rewritten = windowsScript.readText().replace(classPathLine) { "set CLASSPATH=%APP_HOME%\\lib\\*" }
        windowsScript.writeText(rewritten)
    }
}
