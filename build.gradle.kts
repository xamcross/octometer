// The root build of Octometer. The Kotlin plugin loads one time here.
// Each Kotlin module (monitor/backend, kit/jvm-ktor) applies it again with
// the same alias; that keeps one class loader for the plugin.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}
