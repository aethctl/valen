plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

sourceSets {
    main {
        // soundcloud-page.js sits beside its Kotlin, as ytm-page.js sits in libs/ytmusic's resources.
        resources.srcDir("src/main/kotlin")
        resources.include("**/*.js")
    }
}

dependencies {
    api(project(":core"))
    api(project(":libs:web"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test { useJUnitPlatform() }
