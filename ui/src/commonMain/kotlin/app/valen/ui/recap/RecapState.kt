/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.recap

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether "Your week" is on screen. Any screen opens it ([open]); the shell shows it over
 * everything, chrome included, and the story closes itself.
 */
@Stable
class RecapState {
    var shown by mutableStateOf(false)
        private set

    /** The page to start on (the screenshot run's `VALEN_CAPTURE_RECAP`); 0 for the first. */
    var startAt by mutableStateOf(0)
        private set

    fun open(at: Int = 0) {
        startAt = at
        shown = true
    }

    fun close() {
        shown = false
    }

    /** Asks the story on screen to turn to its next page (the screenshot run's `VALEN_CAPTURE_RECAP_TURN`). */
    var turns by mutableStateOf(0)
        private set

    fun turn() {
        turns++
    }
}

/** The app's one recap. */
val LocalRecap = staticCompositionLocalOf { RecapState() }

/** "11 h 6 min", "18 min", "40 s": time listened, in the words the profile uses. */
fun listenedFor(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes >= 60 -> "${minutes / 60} h ${minutes % 60} min"
        minutes >= 1 -> "$minutes min"
        else -> "${ms / 1000} s"
    }
}
