/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.coroutines.flow.StateFlow

/**
 * A version of Valen: MAJOR.MINOR.PATCH, optionally `-alpha.N`, `-beta.N` or `-rc.N`. Updates are
 * compared by [code], one whole number that only grows (Android's `versionCode` is the same number),
 * so a test build sorts below the release it leads to: 1.0.0-alpha.2 < 1.0.0-rc.1 < 1.0.0.
 *
 * Kept in step with the root `build.gradle.kts`, which makes the same number for the build.
 */
data class ValenVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val stage: Stage = Stage.FINAL,
    val n: Int = 0,
) : Comparable<ValenVersion> {

    enum class Stage(val word: String, val rank: Int) {
        ALPHA("alpha", 100),
        BETA("beta", 200),
        RC("rc", 300),
        FINAL("", 999),
    }

    /** The number versions are compared by; it fits Android's `versionCode`. */
    val code: Int get() = (major * 10_000 + minor * 100 + patch) * 1000 + stage.rank + if (stage == Stage.FINAL) 0 else n

    /** A pre-release (alpha, beta, release candidate): offered only to those who take test builds. */
    val isTest: Boolean get() = stage != Stage.FINAL

    override fun compareTo(other: ValenVersion): Int = code.compareTo(other.code)

    override fun toString(): String =
        if (stage == Stage.FINAL) "$major.$minor.$patch" else "$major.$minor.$patch-${stage.word}.$n"

    companion object {
        private val PATTERN = Regex("""^[vV]?(\d{1,2})\.(\d{1,2})\.(\d{1,2})(?:-(alpha|beta|rc)(?:\.(\d{1,2}))?)?$""")

        /** A version or a release tag (`v1.0.0-alpha.3`); null for anything else (the first tag, "ALPHA-1", included). */
        fun parse(text: String): ValenVersion? {
            val m = PATTERN.matchEntire(text.trim()) ?: return null
            val (major, minor, patch, word, n) = m.destructured
            val stage = Stage.entries.firstOrNull { it.word == word && word.isNotEmpty() } ?: Stage.FINAL
            return ValenVersion(major.toInt(), minor.toInt(), patch.toInt(), stage, if (stage == Stage.FINAL) 0 else n.toIntOrNull() ?: 0)
        }
    }
}

/**
 * One file of a release, as the release's manifest (`valen-release.json`) names it: which build it
 * is for, its size and its SHA-256, so a download is installed only when it is exactly that file.
 */
@kotlinx.serialization.Serializable
data class ReleaseAsset(
    /** `linux-appimage`, `linux-deb` or `android`. */
    val platform: String,
    val name: String,
    val size: Long,
    val sha256: String,
    /** Android: the SHA-256 of the signing certificate, so a differently signed APK is refused before the system is asked. */
    val signer: String? = null,
)

/** `valen-release.json`, written by the release workflow beside the files it describes. */
@kotlinx.serialization.Serializable
data class ReleaseManifest(
    val version: String,
    val code: Int,
    val assets: List<ReleaseAsset> = emptyList(),
)

/** A release newer than this build, with the one file that fits it. */
data class AppRelease(
    val version: String,
    val code: Int,
    val test: Boolean,
    val asset: ReleaseAsset,
    /** The release's page on GitHub. */
    val page: String,
    /** Where the file is downloaded from. */
    val url: String,
    /** The release's notes as GitHub has them (Markdown); empty when it has none. */
    val notes: String = "",
)

/** How this copy of Valen is updated. */
enum class UpdateRoute {
    /** Valen replaces itself (the AppImage) and the new version starts with the next start, or now with Restart. */
    Itself,

    /** The system installs it (Android's installer, the desktop's package tool), after Valen downloads it. */
    System,

    /** Valen only says that a release is out: a build from source, or a copy Nix or another tool manages. */
    Tell,
}

/** Where the updater is. Every state is a real one; nothing is shown as done before it is. */
sealed interface UpdateState {
    /** Not looking: updates are switched off. */
    data object Off : UpdateState

    /** Not looked yet in this run. */
    data object Idle : UpdateState

    data object Checking : UpdateState

    /** Nothing newer, as of [at] (wall clock, ms). */
    data class UpToDate(val at: Long) : UpdateState

    /** A newer release, not downloaded yet. */
    data class Available(val release: AppRelease) : UpdateState

    data class Downloading(val release: AppRelease, val done: Long, val total: Long) : UpdateState {
        val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    }

    /** Downloaded and checked; waiting for the press that hands it to the system. */
    data class Ready(val release: AppRelease) : UpdateState

    /** Handed over: the system's installer (or the package tool) has it now. */
    data class Installing(val release: AppRelease) : UpdateState

    /** In place: the new version starts with Valen's next start (or now, with Restart). */
    data class Installed(val release: AppRelease) : UpdateState

    /** It went wrong; [reason] is said as it is. Checking again, or Update, tries once more. */
    data class Failed(val reason: String, val release: AppRelease? = null) : UpdateState
}

/** What the person chose about updates (Settings → About). */
@kotlinx.serialization.Serializable
data class UpdatePrefs(
    /** Look for new releases a few times a day, and download one as soon as it is out. */
    val check: Boolean = true,
    /** Take test builds (pre-releases) too. Null follows this build: a test build takes them, a release does not. */
    val testBuilds: Boolean? = null,
    /** "Later" on the update sheet: the release it was pressed for (its number), and until when (wall clock, ms). */
    val laterCode: Int = 0,
    val laterUntil: Long = 0,
) {
    fun takesTestBuilds(current: ValenVersion?): Boolean = testBuilds ?: (current?.isTest ?: true)

    /** Whether the update sheet may open by itself for [release] at [now]: not while "Later" holds for it. */
    fun mayShow(release: AppRelease, now: Long): Boolean = !(laterCode == release.code && now < laterUntil)

    /** "Later" for [release]: the sheet stays away for [LATER_MS]; a newer release shows at once. */
    fun later(release: AppRelease, now: Long): UpdatePrefs = copy(laterCode = release.code, laterUntil = now + LATER_MS)

    companion object {
        /** How long "Later" keeps the update sheet away: a day. */
        const val LATER_MS = 24 * 60 * 60_000L
    }
}

/** A piece of a release's notes, read from GitHub's Markdown. */
sealed interface NotePiece {
    data class Heading(val text: String) : NotePiece

    data class Item(val text: String) : NotePiece

    data class Line(val text: String) : NotePiece
}

/**
 * A release's notes as a few plain pieces: headings, list items and lines, with Markdown's marks
 * taken off. GitHub's generated notes are mostly links; a pull request's link becomes "#2", a
 * profile stays "@name", and the "Full Changelog" line is left out (the release page has it).
 */
fun releaseNotes(markdown: String, most: Int = 14): List<NotePiece> {
    val pieces = mutableListOf<NotePiece>()
    for (raw in markdown.replace("\r", "").lines()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("<!--")) continue
        if (line.contains("Full Changelog", ignoreCase = true)) continue
        val piece = when {
            line.startsWith("#") -> NotePiece.Heading(plainNote(line.trimStart('#')))
            line.startsWith("* ") || line.startsWith("- ") || line.startsWith("+ ") -> NotePiece.Item(plainNote(line.drop(2)))
            else -> NotePiece.Line(plainNote(line))
        }
        val text = when (piece) {
            is NotePiece.Heading -> piece.text
            is NotePiece.Item -> piece.text
            is NotePiece.Line -> piece.text
        }
        if (text.isNotBlank()) pieces += piece
        if (pieces.size >= most) break
    }
    // A heading with nothing under it says nothing.
    while (pieces.lastOrNull() is NotePiece.Heading) pieces.removeAt(pieces.lastIndex)
    return pieces
}

private val PULL = Regex("""\s*in\s+https://github\.com/[^/\s]+/[^/\s]+/pull/(\d+)""")
private val LINK = Regex("""\[([^\]]*)\]\(([^)]*)\)""")
private val URL = Regex("""https?://\S+""")
private val MARKS = Regex("""(\*\*|__|`)""")

private fun plainNote(text: String): String = text
    .replace(PULL) { " (#${it.groupValues[1]})" }
    .replace(LINK) { it.groupValues[1] }
    .replace(URL, "")
    .replace(MARKS, "")
    .replace(Regex("""\s+"""), " ")
    .trim()
    .trimEnd(':')

/** Valen's updates, as the UI sees them. One per app; none in tests that do not ask for it. */
interface Updates {
    /** This build's version, as it is named. */
    val current: String

    val route: UpdateRoute

    /** For [UpdateRoute.Tell]: how this copy gets a new version, in one sentence. */
    val elsewhere: String?

    val state: StateFlow<UpdateState>

    /** Look now, whatever the last look was. */
    fun check()

    /** Download the release found (when it is not yet), then install it. */
    fun update()

    /** [UpdateState.Installed] on the desktop: close Valen and start the new version. */
    fun restart()

    /** The release's page, for a copy that is updated elsewhere. */
    fun openPage(release: AppRelease)
}

/** The word Valen says once when a release is ready (a confirmation that leaves by itself); null for any other state. */
fun announcement(state: UpdateState): String? = when (state) {
    is UpdateState.Installed -> "Valen ${state.release.version} is ready. It opens next time"
    is UpdateState.Ready -> "Valen ${state.release.version} is ready to install, in Settings"
    is UpdateState.Available -> "Valen ${state.release.version} is out"
    else -> null
}
