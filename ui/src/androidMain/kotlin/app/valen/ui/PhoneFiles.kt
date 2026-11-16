/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import app.valen.core.LocalPlaylistExtensions
import app.valen.core.parseLocalPlaylist
import java.io.File

/**
 * What the system chooser hands back (a `content://` document), turned into the path Valen's
 * device source reads: shared storage and SD cards by their documents' ids, the media and
 * downloads providers through the media index.
 */
internal object PhoneFiles {

    /** Playlist files under every name file managers give them, and single songs. */
    val PickTypes = arrayOf(
        "audio/*",
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "application/xspf+xml",
        "application/octet-stream",
        "text/*",
    )

    /** The phone's music: Android 13 asks for audio alone; before it, storage (and writing, on 10). */
    val permissions: List<String> = when {
        Build.VERSION.SDK_INT >= 33 -> listOf(Manifest.permission.READ_MEDIA_AUDIO)
        Build.VERSION.SDK_INT == 29 -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private const val EXTERNAL = "com.android.externalstorage.documents"
    private const val MEDIA = "com.android.providers.media.documents"
    private const val DOWNLOADS = "com.android.providers.downloads.documents"

    fun pathOf(context: Context, uri: Uri, folder: Boolean): String? {
        val mapped = runCatching { mapped(context, uri, folder) }.getOrNull()
        if (folder) return mapped?.takeIf { File(it).isDirectory }
        if (mapped != null && File(mapped).canRead()) return mapped
        // A playlist file Valen may not open by its path (Android lets an app read music files,
        // not every list), or one from elsewhere: read through the chooser's grant, and kept as an
        // m3u8 whose songs are named by full path, so they are still found where they are.
        return runCatching { copied(context, uri, mapped) }.getOrNull()
    }

    private fun mapped(context: Context, uri: Uri, folder: Boolean): String? {
        val id = if (folder) DocumentsContract.getTreeDocumentId(uri) else DocumentsContract.getDocumentId(uri)
        return when (uri.authority) {
            EXTERNAL -> {
                val volume = id.substringBefore(':')
                val rest = id.substringAfter(':', "")
                val base = if (volume.equals("primary", ignoreCase = true)) Environment.getExternalStorageDirectory().path else "/storage/$volume"
                if (rest.isEmpty()) base else "$base/$rest"
            }
            DOWNLOADS -> when {
                id.startsWith("raw:") -> id.removePrefix("raw:")
                else -> null
            }
            MEDIA -> {
                val kind = id.substringBefore(':')
                val number = id.substringAfter(':').toLongOrNull() ?: return null
                val collection = when (kind) {
                    "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    else -> MediaStore.Files.getContentUri("external")
                }
                dataOf(context, ContentUris.withAppendedId(collection, number))
            }
            else -> null
        }
    }

    @Suppress("DEPRECATION")
    private fun dataOf(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun copied(context: Context, uri: Uri, mapped: String?): String? {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: mapped?.let { File(it).name } ?: "Playlist.m3u8"
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension !in LocalPlaylistExtensions) return null
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: return null
        val folder = mapped?.let { File(it).parent } ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).path
        val parsed = parseLocalPlaylist(text, folder, extension)
        val out = File(File(context.filesDir, "picked"), name.substringBeforeLast('.') + ".m3u8")
        out.parentFile?.mkdirs()
        out.writeText(
            buildString {
                append("#EXTM3U\n")
                append("#PLAYLIST:").append(parsed.name ?: name.substringBeforeLast('.')).append('\n')
                for (e in parsed.entries) {
                    append("#EXTINF:").append(e.durationMs / 1000).append(',')
                    if (!e.artist.isNullOrBlank()) append(e.artist).append(" - ")
                    append(e.title ?: File(e.path).nameWithoutExtension).append('\n')
                    append(e.path).append('\n')
                }
            },
        )
        return out.absolutePath
    }

    /** Opens shared storage's folders in the Files app; nothing for an app's own storage. */
    fun opener(context: Context, path: String): (() -> Unit)? {
        val base = Environment.getExternalStorageDirectory().absolutePath
        if (!path.startsWith("$base/") || path.startsWith("$base/Android/")) return null
        val relative = path.removePrefix("$base/").trimEnd('/')
        return {
            val uri = DocumentsContract.buildDocumentUri(EXTERNAL, "primary:$relative")
            val intents = listOf(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR),
                Intent(Intent.ACTION_VIEW, uri),
            )
            for (intent in intents) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (runCatching { context.startActivity(intent) }.isSuccess) break
            }
        }
    }
}
