/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.update

import app.valen.core.AppRelease
import app.valen.core.UpdateRoute
import java.io.File

/**
 * The platform's half of an update: which release file this copy of Valen takes, and what is done
 * with it once it is downloaded and its SHA-256 matches the release's manifest.
 */
interface UpdateInstaller {
    val route: UpdateRoute

    /** The manifest's platform this copy takes (`linux-appimage`, `linux-deb`, `android`); null when none fits. */
    val platform: String?

    /** For [UpdateRoute.Tell]: how this copy gets a new version, in one sentence. */
    val elsewhere: String? get() = null

    /** Whether a download may start without a press now (Android: not on a metered network). */
    fun mayDownloadQuietly(): Boolean = true

    /** Whether the system install may start without a press (the AppImage swap does; Android asks). */
    val installsQuietly: Boolean get() = route == UpdateRoute.Itself

    /** Called off the main thread with the downloaded file, already checked against the manifest. */
    suspend fun install(file: File, release: AppRelease): Outcome

    /** Close Valen and start the version just put in place. */
    fun restart() {}

    /** Open an address in the browser (a release's page). */
    fun open(url: String) {}

    sealed interface Outcome {
        /** The new version is in place and starts with the next start. */
        data object InPlace : Outcome

        /** The system's installer has it now; it reports back through [GithubUpdater.installerSaid]. */
        data object HandedOver : Outcome

        data class Failed(val reason: String) : Outcome
    }
}

/** What a system installer reported after it was handed an update. */
sealed interface InstallerReport {
    /** The person closed the system's question: the update stays ready. */
    data object Cancelled : InstallerReport

    data class Failed(val reason: String) : InstallerReport
}
