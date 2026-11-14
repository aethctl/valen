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

    /** One screen replacing another in the same place: a short fade, no travel. */
    const val PageMs = 160

    /** A control changing colour or fill (hover, selection, the dock picking up a new cover). */
    const val TintMs = 600

    /** The artwork wash drifting to a new song's colours: slow enough to be felt, not watched. */
    const val AmbientMs = 1400

    /** One cycle of the playing bars. */
    const val PlayingBarsMs = 1400

    /** One full turn of the stage wash's drift, in seconds. */
    const val AmbientLoopSeconds = 96f

    /** A confirmation toast stays this long; a provider's message stays until dismissed. */
    const val ToastMs = 2600L

    /** Reduced motion: short fades, no travel. */
    const val ReducedMs = 120
    const val ReducedTweenMs = 160

    fun <T> standard(): FiniteAnimationSpec<T> = tween(StandardMs, easing = Standard)
    fun <T> enter(): FiniteAnimationSpec<T> = tween(EnterMs, easing = Enter)
    fun <T> exit(): FiniteAnimationSpec<T> = tween(ExitMs, easing = Exit)
    fun <T> press(): FiniteAnimationSpec<T> = tween(PressMs, easing = Standard)
    fun <T> fade(): FiniteAnimationSpec<T> = tween(ReducedMs)
    fun <T> page(): FiniteAnimationSpec<T> = tween(PageMs, easing = Standard)
    fun <T> tint(): FiniteAnimationSpec<T> = tween(TintMs, easing = Standard)
    fun <T> ambient(): FiniteAnimationSpec<T> = tween(AmbientMs, easing = Standard)

    /** Queue rows passing each other: stiff and well damped, so nothing wobbles. */
    val QueueReorder: SpringSpec<IntOffset> = SpringSpec(stiffness = 520f, dampingRatio = 0.83f)

    /** The selection pill sliding between segments or tabs: one soft settle, no bounce back. */
    fun <T> chrome(): SpringSpec<T> = SpringSpec(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.82f)

    /** The scale a pressed control settles at. */
    const val PressedScale = 0.97f

    /** Artwork enters with the stage rather than popping in. */
    val StageSpring: SpringSpec<Float> = SpringSpec(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioNoBouncy)
}

/** Set by the host when the operating system asks for less movement. */
val LocalReducedMotion = compositionLocalOf { false }
