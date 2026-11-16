plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("com.android.kotlin.multiplatform.library")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
    jvm()
    android {
        namespace = "app.valen.ui"
        compileSdk = 37
        minSdk = 29
        // The KMP library plugin packs no Android resources unless asked: without this the APK had
        // none of the Compose resources — no Bricolage or Inter (every screen fell back to the
        // system font) and no TV frames (the welcome's picture step crashed the app).
        androidResources { enable = true }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.okhttp)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
        }
        jvmTest.dependencies {
            implementation(libs.junit.jupiter)
            implementation(libs.kotlinx.coroutines.test)
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
            runtimeOnly(libs.junit.launcher)
        }
    }
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
