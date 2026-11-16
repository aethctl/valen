import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin { jvmToolchain(21) }

// Lyprio's address and signing key are the builder's own, never in the source: a Gradle property,
// the environment, or the git-ignored local.properties (the names Metrolist uses, which also carry
// into an Android build from the same file). Without them the desktop asks LRCLIB alone.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun lyprioSetting(name: String): String =
    (providers.gradleProperty(name).orNull ?: System.getenv(name) ?: localProperties.getProperty(name)).orEmpty().trim()
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val generateLyprioConfig = tasks.register("generateLyprioConfig") {
    val url = lyprioSetting("LYPRIO_BASE_URL")
    val key = lyprioSetting("LYPRIO_HMAC_KEY")
    inputs.property("url", url)
    inputs.property("key", key)
    val out = layout.buildDirectory.dir("generated/lyprio/kotlin")
    outputs.dir(out)
    doLast {
        val file = out.get().file("app/valen/desktop/LyprioBuild.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            /* SPDX-License-Identifier: GPL-3.0-only */
            package app.valen.desktop

            /** Lyprio's endpoint as this build was given it; both are empty when it was not (see Lyrics.kt). */
            internal object LyprioBuild {
                const val URL = ${quoted(url)}
                const val KEY = ${quoted(key)}
            }

            """.trimIndent(),
        )
    }
}

kotlin.sourceSets["main"].kotlin.srcDir(generateLyprioConfig)

dependencies {
    implementation(project(":core"))
    implementation(project(":ui"))
    implementation(project(":providers:spotify"))
    implementation(project(":providers:native"))
    implementation(project(":providers:lyrics"))
    implementation(project(":libs:audio"))
    implementation(project(":host:desktop"))
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.dbus.core)
    implementation(libs.dbus.unix)
    implementation(compose.desktop.currentOs)
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test { useJUnitPlatform() }

compose.desktop {
    application {
        mainClass = "app.valen.desktop.MainKt"
        // XWayland on a tiling compositor leaves AWT's content window at the size it was created
        // with (see tellAwtWhenTheWindowManagerDoesNotReparent in Main.kt). Reaching that switch
        // means opening the package that holds it; the two properties are the companion
        // workaround from the same upstream report (Gitnuro #333) and cost nothing elsewhere.
        jvmArgs += listOf(
            "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED",
            "-Dsun.awt.noerasebackground=true",
            "-Dsun.java2d.pmoffscreen=false",
            // Memory. The JVM's defaults are sized for a server: a heap allowed a quarter of the
            // machine's RAM that is kept at its peak once grown, and a collector thread per core
            // with its own bookkeeping. A player's heap stays under 100 MB (measured with a song
            // and its lyrics playing). G1 gives unused heap back to the system after 5 s at rest,
            // keeps at most 30 % of it free, and two collector threads are plenty.
            // Resident memory: 455 MB → 396 MB in the same capture, and it no longer creeps.
            "-XX:+UseG1GC",
            "-Xmx1g",
            "-XX:G1PeriodicGCInterval=5000",
            "-XX:MinHeapFreeRatio=10",
            "-XX:MaxHeapFreeRatio=30",
            "-XX:ParallelGCThreads=2",
            "-XX:ConcGCThreads=1",
            "-XX:+UseStringDeduplication",
        )
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.AppImage)
            packageName = "Valen"
            packageVersion = "1.0.0"
            description = "A music player for Spotify, YouTube Music and SoundCloud"
            // dbus-java reads the uid with com.sun.security.auth.module.UnixSystem; the
            // trimmed runtime Compose builds leaves jdk.security.auth out, and MPRIS then
            // fails with "No session bus: com/sun/security/auth/module/UnixSystem".
            // The Chrome launcher probes the DevTools port with java.net.http, which the
            // trimmed runtime leaves out too (NoClassDefFoundError: java/net/http/HttpClient).
            modules("jdk.security.auth", "java.net.http")
            vendor = "Valen"
            copyright = "© 2026 Valen contributors. GPL-3.0-only."
            licenseFile.set(rootProject.file("LICENSE"))
            linux {
                iconFile.set(project.file("src/main/resources/valen.png"))
                // Without these the entry says Categories=Unknown and no menu shows it.
                // One token only: Compose writes it into both the .desktop Categories and the
                // Debian Section field, and a section list there is not a valid control field.
                appCategory = "AudioVideo"
                menuGroup = "AudioVideo"
                debMaintainer = "Valen contributors"
                shortcut = true
            }
        }
    }
}
