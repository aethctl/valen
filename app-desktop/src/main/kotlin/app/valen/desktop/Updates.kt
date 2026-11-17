/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.desktop

import app.valen.core.AppRelease
import app.valen.core.UpdateRoute
import app.valen.update.UpdateInstaller
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.map

/**
 * How this copy of Valen on Linux is updated, decided once from where it runs:
 *
 * - **An AppImage** (`$APPIMAGE`): Valen replaces its own file. The new one is written beside it and
 *   moved over it in one step, so the file is always a whole AppImage; the running one keeps its
 *   mounted copy until it closes. Restart starts the new file once this process is gone.
 * - **The .deb** (`/opt/valen`): the package tool installs it (`pkexec apt-get`, which asks for the
 *   password in the desktop's own window), or the desktop's installer opens it.
 * - **Nix** (anything under `/nix/store`) and **a build from source**: Valen only says a release is
 *   out. Nix copies are the flake's to update; replacing a store path is not Valen's to do.
 */
internal class DesktopInstaller(private val quit: () -> Unit) : UpdateInstaller {

    private val exe: Path? = runCatching { Files.readSymbolicLink(Paths.get("/proc/self/exe")) }.getOrNull()
    private val appImage: Path? = System.getenv("APPIMAGE")?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Paths.get(it).toRealPath() }.getOrNull() }
        ?.takeIf { Files.isRegularFile(it) }
    private val fromNix = listOfNotNull(exe, appImage).any { it.startsWith("/nix/store") } ||
        System.getenv("APPDIR")?.startsWith("/nix/store") == true
    private val fromDeb = exe?.startsWith("/opt/valen") == true

    override val route: UpdateRoute = when {
        fromNix -> UpdateRoute.Tell
        appImage != null -> UpdateRoute.Itself
        fromDeb -> UpdateRoute.System
        else -> UpdateRoute.Tell
    }

    override val platform: String? = when (route) {
        UpdateRoute.Itself -> "linux-appimage"
        UpdateRoute.System -> "linux-deb"
        UpdateRoute.Tell -> null
    }

    override val elsewhere: String? = when {
        fromNix -> "This copy comes from Nix, so it is updated with the flake."
        route == UpdateRoute.Tell -> "This is a build from source. Releases are on GitHub."
        else -> null
    }

    override suspend fun install(file: File, release: AppRelease): UpdateInstaller.Outcome = when (route) {
        UpdateRoute.Itself -> replaceAppImage(file)
        UpdateRoute.System -> installDeb(file)
        UpdateRoute.Tell -> UpdateInstaller.Outcome.Failed("This copy is not updated by Valen")
    }

    private fun replaceAppImage(file: File): UpdateInstaller.Outcome {
        val target = appImage ?: return UpdateInstaller.Outcome.Failed("Valen could not find its AppImage")
        val dir = target.parent
        if (!Files.isWritable(dir)) {
            return UpdateInstaller.Outcome.Failed("Valen cannot write to ${dir}, so it cannot replace itself there")
        }
        // Written in the same folder first, so the move is a rename on one file system: the
        // AppImage's name always holds a whole file, the old one or the new one.
        val next = dir.resolve(".${target.fileName}.update")
        return try {
            Files.copy(file.toPath(), next, StandardCopyOption.REPLACE_EXISTING)
            Files.setPosixFilePermissions(next, PosixFilePermissions.fromString("rwxr-xr-x"))
            Files.move(next, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            file.delete()
            UpdateInstaller.Outcome.InPlace
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(next) }
            UpdateInstaller.Outcome.Failed("Valen could not replace its AppImage: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun installDeb(file: File): UpdateInstaller.Outcome {
        val pkexec = onPath("pkexec")
        val apt = onPath("apt-get")
        if (pkexec != null && apt != null) {
            val process = ProcessBuilder(pkexec, apt, "install", "-y", "--allow-downgrades", file.absolutePath)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            if (!process.waitFor(15, TimeUnit.MINUTES)) {
                process.destroy()
                return UpdateInstaller.Outcome.Failed("The package tool did not finish")
            }
            return when (process.exitValue()) {
                0 -> UpdateInstaller.Outcome.InPlace
                // pkexec: the password window was closed, or not given.
                126, 127 -> UpdateInstaller.Outcome.Failed("The install was not allowed")
                else -> UpdateInstaller.Outcome.Failed("apt-get could not install the update (${process.exitValue()})")
            }
        }
        // No apt here: the desktop's own software installer opens the package.
        return if (xdgOpen(file.absolutePath)) UpdateInstaller.Outcome.HandedOver
        else UpdateInstaller.Outcome.Failed("Nothing here installs a .deb; it is in ${file.parent}")
    }

    /**
     * Starts the new version once this process has gone (one Valen at a time: the profile, the
     * DevTools port and the MPRIS name are shared), then closes this one the usual way.
     */
    override fun restart() {
        val start = when (route) {
            UpdateRoute.Itself -> appImage?.toString()
            UpdateRoute.System -> exe?.toString()
            UpdateRoute.Tell -> null
        } ?: return
        val pid = ProcessHandle.current().pid().toString()
        val wait = "while kill -0 \"$1\" 2>/dev/null; do sleep 0.3; done; exec \"$2\""
        val command = buildList {
            onPath("setsid")?.let { add(it) }
            addAll(listOf("/bin/sh", "-c", wait, "valen-restart", pid, start))
        }
        runCatching {
            ProcessBuilder(command)
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .also { pb ->
                    // The new AppImage sets its own; the old mount's would point at a folder that goes away.
                    listOf("APPIMAGE", "APPDIR", "ARGV0", "OWD").forEach { pb.environment().remove(it) }
                }
                .start()
        }.onFailure {
            System.err.println("valen: could not start the new version: ${it.message}")
            return
        }
        quit()
    }

    override fun open(url: String) {
        if (url.startsWith("https://")) xdgOpen(url)
    }

    private fun xdgOpen(target: String): Boolean = runCatching {
        ProcessBuilder(onPath("xdg-open") ?: return false, target)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        true
    }.getOrDefault(false)

    private fun onPath(name: String): String? =
        System.getenv("PATH").orEmpty().split(':').filter { it.isNotBlank() }
            .map { File(it, name) }.firstOrNull { it.canExecute() }?.absolutePath
}

/** Where the desktop's updater keeps its state and downloads: `$XDG_CACHE_HOME/valen/updates`. */
internal fun updatesFolder(): File {
    val cache = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }?.let { File(it) }
        ?: File(System.getProperty("user.home"), ".cache")
    return File(File(cache, "valen"), "updates")
}

/** The desktop's updater and how it is joined to the app once the coordinator exists. */
internal class DesktopUpdater(val updates: app.valen.update.GithubUpdater) {
    private var coordinator: app.valen.core.PlaybackCoordinator? = null

    /** Follows Settings → About. A ready release is shown by the UI's update sheet. */
    fun follow(coordinator: app.valen.core.PlaybackCoordinator) {
        this.coordinator = coordinator
        updates.start(coordinator.settings.map { it.updates })
    }
}

internal fun desktopUpdater(scope: kotlinx.coroutines.CoroutineScope, quit: () -> Unit): DesktopUpdater {
    val http = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    val updates = app.valen.update.GithubUpdater(
        current = ValenBuild.VERSION,
        repo = System.getenv("VALEN_UPDATE_REPO")?.takeIf { it.contains('/') } ?: ValenBuild.UPDATE_REPO,
        installer = DesktopInstaller(quit),
        http = http,
        scope = scope,
        folder = updatesFolder(),
    )
    return DesktopUpdater(updates)
}
