# kit/jvm-ktor

The Ktor adapter of the kit (design decision D17). It mounts the ingest
route of design section 4.2 with one function, `octometerIngestRoute`.

## Minimum versions

- **Java 21.** `kit/jvm-ktor/build.gradle.kts` sets `jvmToolchain(21)`. The
  compiled class files hold major version 65, the Java 21 class file
  format (design decision D17). An app needs a JDK 21 runtime or newer.
- **Kotlin 2.0.** This module compiles with `apiVersion` and
  `languageVersion` pinned to Kotlin 2.0
  (`kit/jvm-ktor/build.gradle.kts`). Kotlin 2.0 is the lowest version that
  the module's own compiler (2.4.20) accepts without an error, confirmed
  on 2026-09-21: version 1.6 gives the compiler error "Language version
  1.6 is no longer supported", and version 2.0 compiles with a
  deprecation warning only ("Update the version to 2.2"). An app on
  Kotlin 2.0, 2.1, or 2.2 can read the class files of this module.
  `kit/jvm-ktor/build.gradle.kts` also sets `coreLibrariesVersion =
  "2.0.0"`, so the published POM names `kotlin-stdlib:2.0.0`, and not the
  compiler's own 2.4.20. Without this setting, an app on Kotlin 2.0, 2.1,
  or 2.2 would fail to compile with a metadata version error (PR #155,
  the JitPack review of issue #37).
- **Ktor 3.2.0.** Ktor is a `compileOnly` dependency (design decision
  D17), thus the app brings its own Ktor 3 version. This module reads the
  request body with `ByteReadChannel.readRemaining(Long)`, because
  `ByteReadChannel.readBuffer(Long)` (the Ktor 3.6.0 replacement) does not
  exist in Ktor 3.2.0. Confirmed on 2026-09-21 with `javap` against
  `ktor-io-jvm-3.2.0.jar` and `ktor-io-jvm-3.6.0.jar`:
  `readRemaining(Long)` exists in each jar; `readBuffer(Long)` exists only
  in the 3.6.0 jar. The module was also compiled and tested against Ktor
  3.2.0 on 2026-09-22, as a temporary local check (not a committed
  dependency change); `./gradlew :kit:jvm-ktor:test` gave `BUILD
  SUCCESSFUL`.

## OCTOMETER_PATH_PATTERNS

A run of whitespace separates each route pattern of `OCTOMETER_PATH_PATTERNS` (a space, a tab, or a line break).

## The store dispatcher

`octometerIngestRoute` runs the store call inside a dispatcher (design
decision D23). The default, `defaultStoreDispatcher()`, is a view of
`Dispatchers.IO` limited to 8 tasks at the same time
(`Dispatchers.IO.limitedParallelism(8)`), so the route never fills the
whole shared `Dispatchers.IO` pool of the app. The app can give
`storeDispatcher` a different value.
