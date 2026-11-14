import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":core"))
    implementation(project(":ui"))
    implementation(project(":providers:spotify"))
    implementation(project(":providers:native"))
    implementation(project(":libs:audio"))
    implementation(project(":host:desktop"))
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.dbus.core)
    implementation(libs.dbus.unix)
    implementation(compose.desktop.currentOs)
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
}

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
