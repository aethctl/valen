plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

// Valen's updates from its GitHub releases: the look, the download, the checks before install.
// A plain JVM library like providers/lyrics, so Android takes it as it is; each app brings its own
// installer (the AppImage swapped in place, Android's PackageInstaller, the desktop's package tool).
dependencies {
    api(project(":core"))
    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test { useJUnitPlatform() }
