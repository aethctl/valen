plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}

// Valen's version, read once for every module: the name, and the number Android and the updater
// compare. Keep in step with core's ValenVersion (core/src/commonMain/.../Updates.kt).
val valenVersion: String = (findProperty("valen.version") as String? ?: error("valen.version is not set")).trim()
val valenVersionCode: Int = run {
    val m = Regex("""^(\d{1,2})\.(\d{1,2})\.(\d{1,2})(?:-(alpha|beta|rc)(?:\.(\d{1,2}))?)?$""").matchEntire(valenVersion)
        ?: error("valen.version '$valenVersion' is not MAJOR.MINOR.PATCH[-alpha|beta|rc[.N]]")
    val (major, minor, patch, stage, n) = m.destructured
    val pre = when (stage) {
        "" -> 999
        "alpha" -> 100
        "beta" -> 200
        else -> 300
    } + (n.toIntOrNull() ?: 0)
    ((major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()) * 1000) + pre
}
extra["valenVersion"] = valenVersion
extra["valenVersionCode"] = valenVersionCode

// `./gradlew -q printVersion` → "1.0.0-alpha.2 10000102", for the release workflow's manifest.
tasks.register("printVersion") {
    val line = "$valenVersion $valenVersionCode"
    doLast { println(line) }
}
