/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * What Discord shows of what Valen plays ("Listening to Valen"), set in Settings → Discord.
 *
 * Discord names an activity after an application registered on its developer portal. Valen ships
 * its own id (`DEFAULT_APP_ID` in the desktop's `DiscordPresence`), so nothing here names one;
 * `VALEN_DISCORD_APP_ID` is the only way to use an application of one's own. An application id is
 * a public identifier, not a secret.
 */
@Serializable
data class PresenceSettings(
    val enabled: Boolean = false,
    /** The song's cover as the large picture (else Valen's own mark). */
    val cover: Boolean = true,
    /** The time bar under the song (Discord draws it from the song's start and end). */
    val progress: Boolean = true,
    /** The line being sung, when the song has timed lyrics, takes the artist's line for a moment. */
    val lyricLine: Boolean = false,
    /** Which service the song plays from, under the cover. */
    val service: Boolean = true,
    /** Buttons on the profile: the song's own page, and Valen's. */
    val buttons: Boolean = true,
    /** While paused the card stays (saying so) for this long, then goes. 0 keeps it. */
    val pausedClearMinutes: Int = 5,
)

/** Where the link to Discord stands, said in Settings. */
sealed interface PresenceStatus {
    data object Off : PresenceStatus
    data object NeedsAppId : PresenceStatus

    /** Discord is not running (no socket to talk to); Valen keeps looking. */
    data object NoDiscord : PresenceStatus
    data object Connecting : PresenceStatus
    data class Connected(val user: String?) : PresenceStatus
    data class Failed(val reason: String) : PresenceStatus
}

/** The platform's link to Discord; only the desktop has one. */
interface PresenceLink {
    val status: StateFlow<PresenceStatus>
}
