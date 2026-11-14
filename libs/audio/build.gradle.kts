plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":core"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    // Resolves a YouTube video id to a stream URL: signatures, the n challenge and PO tokens are
    // the extractor's problem, not ours. GPL-3.0, the licence Valen already uses.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.27.1")

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test { useJUnitPlatform() }
