plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.valen.host.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core"))
    api(project(":libs:web"))
    api(project(":libs:spotify"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.media)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    testImplementation("junit:junit:4.13.2")
}
