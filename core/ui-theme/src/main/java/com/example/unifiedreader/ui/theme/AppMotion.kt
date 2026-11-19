package com.example.unifiedreader.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's motion: three springs, so an interrupted animation keeps its velocity instead of
 * restarting. Spatial moves things (slides, scale, placement), spatialFast is the small
 * feedback under a finger, effects changes opacity and color. Compose finishes every one of
 * them at once when the system animator scale is 0.
 *
 * Page changes (a screen, a settings page, a sheet's page, the bar over them) are the one place
 * that uses fixed lengths instead: the page and its bar must finish together, and a back gesture
 * scrubs the change by its fraction. Every page change has the same shape: the new page fades in
 * over the old one ([pageFadeIn]) while it moves into place ([pageMove]: a small slide one level
 * deeper or back, a small growth between peers), and the old page fades out underneath once the
 * new one covers most of it ([pageFadeOut]). Pages are opaque while they change, so the new page
 * covers the old one instead of the two showing through each other, and the screen never shows
 * the bare ground between two pages. These also follow the system animator scale.
 */
object AppMotion {
    fun <T> spatial(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.8f, stiffness = 380f, visibilityThreshold = visibilityThreshold)

    fun <T> spatialFast(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.6f, stiffness = 800f, visibilityThreshold = visibilityThreshold)

    fun <T> effects(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        spring(dampingRatio = 1f, stiffness = 1600f, visibilityThreshold = visibilityThreshold)

    /** A page change takes this long: the new page's move into place, Material's standard easing. */
    const val pageMillis: Int = 300

    /** The new page fades in over the old one over this, evenly, from the start. */
    const val pageFadeInMillis: Int = 180

    /** The old page stays fully drawn this long, under the new one... */
    const val pageFadeOutDelayMillis: Int = 90

    /** ...then fades out over this, evenly, finishing before the move does. */
    const val pageFadeOutMillis: Int = 150

    /** The new page's move: a slide one level deeper or back, a growth between peers. */
    fun <T> pageMove(): FiniteAnimationSpec<T> = tween(pageMillis, easing = FastOutSlowInEasing)

    /** The new page's fade in. */
    fun pageFadeIn(): FiniteAnimationSpec<Float> = tween(pageFadeInMillis, easing = LinearEasing)

    /** The old page's fade out, after the new one has mostly covered it. */
    fun pageFadeOut(): FiniteAnimationSpec<Float> =
        tween(pageFadeOutMillis, delayMillis = pageFadeOutDelayMillis, easing = LinearEasing)

    /**
     * What leaves with nothing covering it (a bar's old contents, a panel closing over the screen)
     * fades out at once, over the time the new page takes to fade in.
     */
    fun pageFadeAway(): FiniteAnimationSpec<Float> = tween(pageFadeInMillis, easing = LinearEasing)

    /** Between peers (Library, Lists, Settings; opening a book) the new page grows from this scale to 1. */
    const val pageInitialScale: Float = 0.98f

    /** A panel that opens in place (the search panel, the finished card) grows from this scale to 1. */
    const val panelInitialScale: Float = 0.96f

    /** One level deeper or back, both pages slide this far, forward toward the start edge. */
    val sharedAxisOffset: Dp = 30.dp

    /** Covers, chips and buttons shrink to this while pressed. */
    const val pressedScale: Float = 0.96f

    /** A pressed pill button rounds toward a square with corners this size. */
    val pressedCornerRadius: Dp = 14.dp

    /**
     * Predictive back, at full progress: the screen or panel being left has moved this far
     * (screens with the finger, panels toward their own edge)...
     */
    val predictiveBackShift: Dp = 60.dp

    /** ...has shrunk by this fraction... */
    const val predictiveBackShrink: Float = 0.12f

    /** ...and a screen has rounded its corners to this. */
    val predictiveBackCornerRadius: Dp = 28.dp

    /** The screen underneath grows from this scale to 1 as the gesture goes. */
    const val predictiveBackUnderneathScale: Float = 0.94f
}
