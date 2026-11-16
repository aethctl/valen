/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * One motion vocabulary. Every animation in the app names a token from here; nothing invents a
 * duration or an easing at the call site.
 */
object Motion {
    /** The default for anything that changes while the user watches. */
    const val StandardMs = 280
    val Standard: Easing = CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f)

    /** Screens and sheets arriving. */
    const val EnterMs = 320
    val Enter: Easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

    /** Anything leaving. Exits are quicker than arrivals, always. */
    const val ExitMs = 200
    val Exit: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    const val PressMs = 120
    const val ArtworkCrossfadeMs = 320
    const val SheetEnterMs = 320
    const val SheetExitMs = 240

    /** A quick cross-fade between two screens, kept for reduced motion and sheets. */
    const val PageMs = 160

    /**
     * One screen replacing another: the new one is poured down over the old like paint, its
     * leading edge running ahead in drips, while the old one sinks back and darkens.
     */
    const val PourMs = 620
    val Pour: Easing = CubicBezierEasing(0.55f, 0f, 0.18f, 1f)

    /** How far the wet paint runs ahead of the page it reveals, as a share of the pour. */
    const val PourLead = 0.04f

    /** The page being painted over sinks back this far. */
    const val PouredOverScale = 0.965f
    const val PouredOverAlpha = 0.35f

    /** Writing the tag: the whole name, stroke after stroke, at a marker's pace. */
    const val TagWriteMs = 1500

    /** Letters of a song title rising into place, each this much after the one before. */
    const val KineticStaggerMs = 22
    const val KineticMs = 520

    /** Back-out: a letter overshoots its line a little and settles, like a sticker slapped down. */
    val Kinetic: Easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

    /** One trip of the light around the player's rim while a song plays. */
    const val OrbitMs = 6400

    /** The profile's television: its tube lighting, a burst of static on a new picture, its slow life. */
    const val TubeOnMs = 1100
    const val StaticMs = 650
    const val TvLoopMs = 12000

    /** How far the set turns by itself, either way, while nothing points at it. */
    const val TvSwayDegrees = 14f

    /** How far a cover on a shelf is turned at rest, at most, like a poster pasted by hand. */
    const val PasteDegrees = 1.6f

    /** Rows, cards and sections arriving with a page: each this much later than the one before. */
    const val StaggerMs = 38

    /** Only the first few items stagger; the rest arrive with the last of them. */
    const val StaggerCap = 10

    /** How long after a page appears its items still rise in; later ones (a scroll) simply appear. */
    const val StaggerWindowMs = 650L

    /** How far an item rises as it arrives. */
    val RiseDistance = 18.dp

    /** A marker line drawn under a title, from its first point to its flick. */
    const val MarkerMs = 720

    /** The paint running down from a marker line once it is drawn. */
    const val DripMs = 900

    /** The light that crosses the player once when a new song starts. */
    const val SweepMs = 1200

    /** The stage cover sits back this far while the song is paused, and comes forward to play. */
    const val PausedScale = 0.9f

    /** How far a cover leans toward the pointer resting on it, in degrees. */
    const val TiltDegrees = 7f

    /** A card under the pointer comes up this much. */
    const val HoverScale = 1.035f

    /** A control changing colour or fill (hover, selection, the dock picking up a new cover). */
    const val TintMs = 600

    /** The artwork wash drifting to a new song's colours: slow enough to be felt, not watched. */
    const val AmbientMs = 1400

    /** One cycle of the playing bars. */
    const val PlayingBarsMs = 1400

    /** The playing bars move in steps this long, like a level meter: never on every frame. */
    const val PlayingBarsStepMs = 110

    /**
     * How long a pointer rests on a song before Valen looks it up ahead: long enough that sweeping
     * across a list asks nothing, short enough to beat the click that usually follows.
     */
    const val IntentHoverMs = 250L

    /** One full turn of the stage wash's drift, in seconds. */
    const val AmbientLoopSeconds = 96f

    /** A confirmation toast stays this long; a provider's message stays until dismissed. */
    const val ToastMs = 2600L

    /**
     * The sound notebook. A note is written at a pen's pace, this long a letter, never longer than
     * [WriteMaxMs] in all; a value that changes is struck out with the marker first; a tab turns
     * its page over the spine; notes written together start this far apart.
     */
    const val WriteLetterMs = 26
    const val WriteMaxMs = 900
    const val StrikeMs = 260
    const val PageTurnMs = 640
    const val NoteStaggerMs = 170

    /** A setting must hold this long before the notebook writes it down: a dragged slider is not a note yet. */
    const val NoteSettleMs = 420L

    /** A struck-out value stays readable this long beside its new one before it fades. */
    const val StruckHoldMs = 1400L

    /**
     * Lyrics. The lines follow the song on a soft spring, and while they travel each line below
     * the sung one lags a little more of what is left to go ([LyricsCascade] a line, up to
     * [LyricsCascadeLines]), so they spread like poured paint and gather where they stop. After a
     * scroll by hand they wait [LyricsResumeMs] before following again.
     */
    fun <T> lyricsFollow(): SpringSpec<T> = SpringSpec(stiffness = 150f, dampingRatio = 0.84f)
    const val LyricsCascade = 0.075f
    const val LyricsCascadeLines = 7
    const val LyricsResumeMs = 2600L

    /** A line coming forward as it is sung, and sitting back once it is over. */
    const val LyricLineMs = 420
    const val LyricRestScale = 0.965f

    /** A word rising a little as its singing begins; it stays up until its line is over. */
    const val WordLiftMs = 360

    /**
     * The pen on sung words. A mark starts [ScribbleAfterMs] after its word ends and takes
     * [ScribbleQuickMs] (a strike, a swipe, a burst), [ScribbleMs] or [ScribbleLongMs] (scratched
     * out, a loop, a box). A mark made while a note is held lasts as long as the note, up to
     * [ScribbleHeldMaxMs].
     */
    const val ScribbleAfterMs = 70L
    const val ScribbleQuickMs = 260
    const val ScribbleMs = 400
    const val ScribbleLongMs = 560
    const val ScribbleHeldMaxMs = 2400

    /**
     * "Your week". Each page stays [StoryPageMs] ([StoryShortMs] for the opening, [StoryLongMs] for
     * a list and the song of the week) unless held; its song starts [StorySongDelayMs] after it
     * lands, so tapping through does not start a song per page; its figures count up over
     * [StoryCountMs]; its items arrive [StoryStepMs] apart. Held, the poster gives a little.
     */
    const val StoryPageMs = 8000
    const val StoryShortMs = 6500
    const val StoryLongMs = 9500
    const val StorySongDelayMs = 320L
    const val StoryCountMs = 1300
    const val StoryStepMs = 110
    const val StoryHoldScale = 0.985f

    /** A hold longer than this hides the story's chrome (a tap is quicker). */
    const val StoryHoldMs = 220L

    /** Reduced motion: short fades, no travel. */
    const val ReducedMs = 120
    const val ReducedTweenMs = 160

    fun <T> standard(): FiniteAnimationSpec<T> = tween(StandardMs, easing = Standard)
    fun <T> enter(): FiniteAnimationSpec<T> = tween(EnterMs, easing = Enter)
    fun <T> exit(): FiniteAnimationSpec<T> = tween(ExitMs, easing = Exit)
    fun <T> press(): FiniteAnimationSpec<T> = tween(PressMs, easing = Standard)
    fun <T> fade(): FiniteAnimationSpec<T> = tween(ReducedMs)
    fun <T> page(): FiniteAnimationSpec<T> = tween(PageMs, easing = Standard)
    fun <T> pour(): FiniteAnimationSpec<T> = tween(PourMs, easing = Pour)
    fun <T> pageOut(): FiniteAnimationSpec<T> = tween(ExitMs, easing = Exit)
    fun <T> morph(): FiniteAnimationSpec<T> = tween(MorphMs, easing = Enter)
    fun <T> tint(): FiniteAnimationSpec<T> = tween(TintMs, easing = Standard)
    fun <T> ambient(): FiniteAnimationSpec<T> = tween(AmbientMs, easing = Standard)

    /** Queue rows passing each other: stiff and well damped, so nothing wobbles. */
    val QueueReorder: SpringSpec<IntOffset> = SpringSpec(stiffness = 520f, dampingRatio = 0.83f)

    /** The selection pill sliding between segments or tabs: one soft settle, no bounce back. */
    fun <T> chrome(): SpringSpec<T> = SpringSpec(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.82f)

    /** The scale a pressed control settles at. */
    const val PressedScale = 0.94f

    /** Press and release: quick in, and a small overshoot on the way back, like something elastic. */
    fun <T> squish(): SpringSpec<T> = SpringSpec(stiffness = 700f, dampingRatio = 0.5f)

    /**
     * The glass droplet travelling between places: a liquid settle, a touch of overshoot. While it
     * moves it stretches along its path by its speed, so it reads as poured, not slid.
     */
    fun <T> liquid(): SpringSpec<T> = SpringSpec(stiffness = 420f, dampingRatio = 0.68f)

    /** A jelly ring-out: the player squashing as it swallows a new song, wobbling twice, settling. */
    fun <T> jelly(): SpringSpec<T> = SpringSpec(stiffness = 300f, dampingRatio = 0.32f)

    /** The player growing out of a drop when the first song starts. */
    fun <T> bloom(): SpringSpec<T> = SpringSpec(stiffness = 170f, dampingRatio = 0.7f)

    /** The droplet's two ends: the one reaching for the new place, and the one it pulls after it. */
    fun <T> liquidLead(): SpringSpec<T> = SpringSpec(stiffness = 900f, dampingRatio = 0.72f)
    fun <T> liquidTrail(): SpringSpec<T> = SpringSpec(stiffness = 240f, dampingRatio = 0.78f)

    /** Covers leaning toward the pointer and back, and the stage cover breathing with play/pause. */
    fun <T> lean(): SpringSpec<T> = SpringSpec(stiffness = 260f, dampingRatio = 0.72f)

    /** Play turning into pause and back. */
    const val MorphMs = 260

    /** Artwork enters with the stage rather than popping in. */
    val StageSpring: SpringSpec<Float> = SpringSpec(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioNoBouncy)
}

/** Set by the host when the operating system asks for less movement. */
val LocalReducedMotion = compositionLocalOf { false }
