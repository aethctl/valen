plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("com.android.kotlin.multiplatform.library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    jvm()
    android {
        namespace = "app.valen.core"
        compileSdk = 36
        minSdk = 29
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            implementation(libs.junit.jupiter)
            implementation(libs.kotlinx.coroutines.test)
            runtimeOnly(libs.junit.launcher)
        }
    }
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
