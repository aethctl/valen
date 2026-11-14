/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.util

/** 3:07, or 1:02:07 for anything over an hour. Negative and unknown durations read as 0:00. */
fun formatTime(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        "${hours}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "${minutes}:${seconds.toString().padStart(2, '0')}"
    }
}

/** "3 songs", "1 song", "No songs" — used where a count is the only honest summary. */
fun trackCount(count: Int): String = when (count) {
    0 -> "No songs"
    1 -> "1 song"
    else -> "$count songs"
}

/**
 * "12 songs · 47 min", or "3 songs · 1 hr 12 min". The length is left out when no song in the
 * list reports one, rather than shown as zero.
 */
fun queueSummary(tracks: List<app.valen.core.Track>): String {
    val count = trackCount(tracks.size)
    val minutes = tracks.sumOf { it.durationMs.coerceAtLeast(0L) } / 60_000L
    if (minutes <= 0L) return count
    val length = if (minutes >= 60L) "${minutes / 60} hr ${minutes % 60} min" else "$minutes min"
    return "$count  ·  $length"
}
