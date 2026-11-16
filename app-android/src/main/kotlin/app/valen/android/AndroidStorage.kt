/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.android

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File

/** Where a phone's music is: the shared `Music` folder, and Valen's own place in it for downloads. */
object AndroidStorage {

    /** The phone's `Music` folder (`/storage/emulated/0/Music`), where playlist files are looked for. */
    fun music(): File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)

    /**
     * `Music/Valen`, which every music app on the phone sees. Android 11 and later let an app put its
     * own songs there with no permission; Android 10 needs the storage one, so without it the songs go
     * to Valen's own folder (`Android/data/…/Music/Valen`), which only Valen plays from.
     */
    fun downloads(context: Context): File {
        val shared = File(music(), "Valen")
        if (writable(shared)) return shared
        val own = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(context.filesDir, "Music")
        return File(own, "Valen")
    }

    /** Tells the phone's media index about a new or changed file, so other players list it at once. */
    fun announce(context: Context, file: File) {
        runCatching { MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null) }
    }

    private fun writable(dir: File): Boolean = runCatching {
        // A playlist file is what the shared folder must take (it takes music files only).
        val lists = File(dir, "Playlists")
        if (!lists.isDirectory && !lists.mkdirs()) return false
        val probe = File(lists, ".valen-check.m3u8")
        probe.writeText("#EXTM3U\n")
        probe.delete()
        true
    }.getOrDefault(false)
}
