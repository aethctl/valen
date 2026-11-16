import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Lyprio's address and signing key are the builder's own, never in the source: a Gradle property,
// the environment, or the git-ignored local.properties (the names Metrolist uses). Without them the
// app asks LRCLIB alone.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun lyprioSetting(name: String): String =
    (providers.gradleProperty(name).orNull ?: System.getenv(name) ?: localProperties.getProperty(name)).orEmpty().trim()
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "app.valen.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.valen"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "LYPRIO_BASE_URL", quoted(lyprioSetting("LYPRIO_BASE_URL")))
        buildConfigField("String", "LYPRIO_HMAC_KEY", quoted(lyprioSetting("LYPRIO_HMAC_KEY")))
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        // The build to use on a phone. A debug build is debuggable: ART runs it with its
        // optimisations held back and Compose with its checks on, which made every screen lag
        // (lyrics on the emulator: 20 ms a frame in debug). R8 shrinks and optimises the libraries
        // (Compose above all); Valen's own classes are kept whole (see proguard-rules.pro). Signed
        // with the debug key so it installs without a keystore; a store release brings its own.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":ui"))
    implementation(project(":providers:spotify"))
    implementation(project(":providers:ytmusic"))
    implementation(project(":providers:soundcloud"))
    implementation(project(":providers:native"))
    implementation(project(":providers:lyrics"))
    implementation(project(":host:android"))
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.media)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    // Applies the libraries' own startup profiles (Compose ships them) on a sideloaded install,
    // so their hot code is compiled ahead instead of interpreted for the first minutes.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
}
