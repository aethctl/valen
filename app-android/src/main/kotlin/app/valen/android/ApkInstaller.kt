/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.valen.core.AppRelease
import app.valen.core.UpdateRoute
import app.valen.update.InstallerReport
import app.valen.update.UpdateInstaller
import java.io.File
import java.security.MessageDigest

/**
 * Android's half of an update: the downloaded APK is checked before the system is asked (it is
 * Valen, newer than this one, and signed with the same key — Android would refuse anything else,
 * with a less useful word), then handed to [PackageInstaller] in a session. On Android 12 and later
 * the session asks for no confirmation once Valen itself installed the app it updates; the first
 * update after a hand install shows the system's question. [UpdateReceiver] hears the answer.
 *
 * A debug build (`app.valen.debug`) is not the app the releases are for, so it is only told.
 */
internal class ApkInstaller(private val app: Context) : UpdateInstaller {

    private val debug = app.packageName.endsWith(".debug")

    override val route: UpdateRoute = if (debug) UpdateRoute.Tell else UpdateRoute.System

    override val platform: String? = if (debug) null else "android"

    override val elsewhere: String? = if (debug) "A debug build. Updates are for the release app." else null

    /** Off a metered network only: a phone on mobile data downloads when Update is pressed. */
    override fun mayDownloadQuietly(): Boolean =
        runCatching { app.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == false }.getOrDefault(false)

    override suspend fun install(file: File, release: AppRelease): UpdateInstaller.Outcome {
        val pm = app.packageManager
        val archive = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return UpdateInstaller.Outcome.Failed("The download is not an app Android can read")
        if (archive.packageName != app.packageName) return UpdateInstaller.Outcome.Failed("The download is a different app")
        val own = pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (archive.longVersionCode <= own.longVersionCode) {
            return UpdateInstaller.Outcome.Failed("The download is not newer than this Valen")
        }
        val theirs = signers(archive)
        val ours = signers(own)
        val listed = release.asset.signer?.lowercase()
        if (theirs.isEmpty() || theirs != ours || (listed != null && listed !in theirs)) {
            return UpdateInstaller.Outcome.Failed(
                "This update is signed with another key, so Android would not install it over this Valen. " +
                    "Install it once by hand from the release page; later updates come by themselves.",
            )
        }
        if (!pm.canRequestPackageInstalls()) {
            runCatching {
                app.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return UpdateInstaller.Outcome.Failed("Allow Valen to install apps, then press Update again")
        }

        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(file.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("valen.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out, 256 * 1024) }
                    session.fsync(out)
                }
                val status = Intent(app, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                session.commit(PendingIntent.getBroadcast(app, id, status, flags).intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            return UpdateInstaller.Outcome.Failed("Android did not take the update: ${e.message ?: e.javaClass.simpleName}")
        }
        return UpdateInstaller.Outcome.HandedOver
    }

    override fun open(url: String) {
        if (!url.startsWith("https://")) return
        runCatching { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** The SHA-256 of each certificate the package is signed with now, as lowercase hex. */
    private fun signers(info: PackageInfo): Set<String> {
        val signing = info.signingInfo ?: return emptySet()
        val certs = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        return certs.orEmpty().map { cert ->
            MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }
}

/** What the system's installer says about an update Valen handed it. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm == null) {
                    ValenRuntime.updater?.installerSaid(InstallerReport.Failed("Android did not ask about the update"))
                    return
                }
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { ValenRuntime.updater?.installerSaid(InstallerReport.Failed("Android's question could not open")) }
            }
            // Valen is replaced and this process ends; the next start is the new version.
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> ValenRuntime.updater?.installerSaid(InstallerReport.Cancelled)
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                ValenRuntime.updater?.installerSaid(InstallerReport.Failed(reason(status, message)))
            }
        }
    }

    private fun reason(status: Int, message: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "Android refused the update: it conflicts with the installed Valen"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This phone cannot run that version"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "There is not enough room on the phone for the update"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "The phone blocked the update"
        PackageInstaller.STATUS_FAILURE_INVALID -> "Android could not read the update"
        else -> message?.takeIf { it.isNotBlank() }?.let { "Android refused the update: $it" } ?: "Android refused the update"
    }

    companion object {
        const val ACTION = "app.valen.UPDATE_STATUS"
    }
}
