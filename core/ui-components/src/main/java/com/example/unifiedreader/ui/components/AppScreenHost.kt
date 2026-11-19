package com.example.unifiedreader.ui.components

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.ui.theme.AppMotion
import kotlin.coroutines.cancellation.CancellationException

/** How an [AppScreenHost] moves from one screen to the next (the values are [AppMotion]'s page change). */
enum class AppScreenTransition {
    /**
     * Between peers (Library, Lists, Journal, Settings; opening a book): the new screen fades in over
     * the old one as it grows from 0.98, and the old one fades out under it.
     */
    Fade,

    /** One level deeper (a series, a shelf, a settings page): both screens slide toward the start as they cross-fade. */
    Forward,

    /** Back up a level: the reverse of [Forward]. */
    Backward,

    /** A plain cut. */
    None,
}

/** Where back leads from the current screen of an [AppScreenHost]. */
class AppScreenBack<S>(
    val target: S,
    val onBack: () -> Unit,
)

/**
 * A back gesture on its way to an [AppScreenHost]: the host scrubs its change to the back
 * target while the gesture is in progress. Screens that must do something of their own on back
 * (a reader saving its place) report through [AppScreenBackHandler] instead of the host's own
 * handler.
 */
@Stable
class AppScreenBackGesture internal constructor() {
    /** The host has somewhere to go back to; otherwise the gesture only triggers back. */
    internal var hasBackTarget by mutableStateOf(false)

    /** The change on screen follows a gesture, from its first event until it has settled. */
    internal var follows by mutableStateOf(false)
    internal var active by mutableStateOf(false)
    internal var progress by mutableFloatStateOf(0f)
    internal var edge by mutableIntStateOf(BackEventCompat.EDGE_LEFT)

    internal fun onProgress(event: BackEventCompat) {
        if (!hasBackTarget) return
        follows = true
        active = true
        edge = event.swipeEdge
        progress = event.progress
    }

    /** Released or cancelled: the host finishes the change if the screen changes, else springs back. */
    internal fun onEnd() {
        active = false
    }
}

/** The back gesture of the nearest [AppScreenHost], for [AppScreenBackHandler]. */
val LocalAppScreenBackGesture = staticCompositionLocalOf<AppScreenBackGesture?> { null }

/**
 * True for a screen that an [AppScreenHost] (or one around it) has moved away from: from the
 * moment its screen changes (Back, or a back gesture let go) until the leaving screen is gone.
 * A back gesture still in progress doesn't count, so a cancelled one never sets it.
 */
val LocalAppScreenLeaving = compositionLocalOf { false }

/**
 * Back for a screen inside an [AppScreenHost] that has to run [onBack] itself (and change the
 * host's screen from there): the predictive gesture still animates the host toward its back
 * target, and [onBack] runs when the gesture is let go.
 */
@Composable
fun AppScreenBackHandler(
    enabled: Boolean = true,
    onBack: () -> Unit,
) {
    val gesture = LocalAppScreenBackGesture.current
    val latestOnBack by rememberUpdatedState(onBack)
    PredictiveBackHandler(enabled = enabled) { events ->
        try {
            events.collect { event -> gesture?.onProgress(event) }
            gesture?.onEnd()
            latestOnBack()
        } catch (_: CancellationException) {
            gesture?.onEnd()
        }
    }
}

/**
 * Shows one screen at a time and animates between them: [AppScreenTransition.Fade] between
 * peers and a 30 dp shared-axis slide between levels, as [transitionFor] decides. A change waits
 * until the new screen has been composed and drawn once (the old one stays as it was meanwhile),
 * then plays: the screen never goes blank between two screens, and a heavy screen's first frame
 * never eats into the change. With [back]
 * set, back is handled here, and a predictive back gesture shows [AppScreenBack.target]
 * underneath while the current screen follows the finger, shrinks and rounds its corners;
 * letting go finishes the change from there, cancelling springs back.
 *
 * Screens with the same [contentKey] share one slot, so changing between them recomposes in
 * place instead of animating.
 *
 * With [ground], each screen is drawn on the window's ground (the background colour and the
 * theme's pattern) while a change plays, so the new screen covers the old one as it fades in
 * instead of the two showing through each other. Use it where the host fills an area that is
 * itself on that ground at the same place (a route on [AppBackground]), so the pattern lines up;
 * not in a sheet, a pane beside another, or over a book.
 */
@Composable
fun <S> AppScreenHost(
    screen: S,
    modifier: Modifier = Modifier,
    back: AppScreenBack<S>? = null,
    contentKey: (S) -> Any? = { it },
    transitionFor: (from: S, to: S) -> AppScreenTransition = { _, _ -> AppScreenTransition.Fade },
    ground: Boolean = false,
    content: @Composable (S) -> Unit,
) {
    AppScreenHost(
        screen = screen,
        frame = Unit,
        modifier = modifier,
        back = back,
        contentKey = contentKey,
        transitionFor = transitionFor,
        ground = ground,
    ) { shown, _ -> content(shown) }
}

/**
 * [AppScreenHost] for screens drawn from state that changes as the screen changes: a leaving
 * screen keeps the last [frame] it was shown with, so it does not draw the new screen's data
 * while it fades.
 */
@Composable
fun <S, F> AppScreenHost(
    screen: S,
    frame: F,
    modifier: Modifier = Modifier,
    back: AppScreenBack<S>? = null,
    contentKey: (S) -> Any? = { it },
    transitionFor: (from: S, to: S) -> AppScreenTransition = { _, _ -> AppScreenTransition.Fade },
    ground: Boolean = false,
    content: @Composable (screen: S, frame: F) -> Unit,
) {
    val seekState = remember { SeekableTransitionState(screen) }
    val latestBack by rememberUpdatedState(back)
    val gesture = remember { AppScreenBackGesture() }
    AppScreenHostBackHandler(back, gesture)

    LaunchedEffect(gesture.active, gesture.progress, screen) {
        val gestureBack = latestBack
        if (gesture.active && gestureBack != null) {
            seekState.seekTo(gesture.progress * GestureShare, targetState = gestureBack.target)
        } else {
            if (seekState.currentState != screen || seekState.targetState != screen) {
                seekState.holdUntilDrawn(screen)
                seekState.animateTo(screen)
            }
            gesture.follows = false
        }
    }

    val frames = remember { HashMap<Any?, F>() }
    val shownKey = contentKey(screen)
    SideEffect {
        frames[shownKey] = frame
        val visibleKeys = setOf(contentKey(seekState.currentState), contentKey(seekState.targetState), shownKey)
        frames.keys.retainAll(visibleKeys)
    }

    val screenTransition = rememberTransition(seekState, label = "AppScreenHost")
    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current
    val leavingAround = LocalAppScreenLeaving.current
    // While a change plays (or a gesture scrubs one), every screen in it is opaque.
    val changing = ground && seekState.currentState != seekState.targetState
    CompositionLocalProvider(LocalAppScreenBackGesture provides gesture) {
        screenTransition.AnimatedContent(
            modifier = modifier,
            contentKey = contentKey,
            transitionSpec = {
                if (gesture.follows) {
                    predictiveBackTransform(gesture.edge, density)
                } else {
                    screenTransform(transitionFor(initialState, targetState), layoutDirection, density)
                }
            },
        ) { shown ->
            val key = contentKey(shown)
            val shownFrame = if (key == shownKey) frame else frames[key] ?: frame
            AppScreenSlot(gesture = gesture, onGround = changing, leaving = leavingAround || key != shownKey) {
                content(shown, shownFrame)
            }
        }
    }
}

/** The host's own back: a predictive gesture scrubs the change to [back]'s target, letting go goes there. */
@Composable
private fun <S> AppScreenHostBackHandler(back: AppScreenBack<S>?, gesture: AppScreenBackGesture) {
    val latestBack by rememberUpdatedState(back)
    SideEffect { gesture.hasBackTarget = back != null }
    // Screens that handle back themselves (AppScreenBackHandler) sit above this one.
    PredictiveBackHandler(enabled = back != null) { events ->
        try {
            events.collect { event -> gesture.onProgress(event) }
            gesture.onEnd()
            latestBack?.onBack?.invoke()
        } catch (_: CancellationException) {
            gesture.onEnd()
        }
    }
}

/**
 * One screen in an [AppScreenHost]: it rounds its corners as it shrinks away under a back gesture,
 * and sits [onGround] (the background colour and the theme's pattern) while a change plays.
 */
@Composable
private fun AnimatedVisibilityScope.AppScreenSlot(
    gesture: AppScreenBackGesture,
    onGround: Boolean,
    leaving: Boolean,
    content: @Composable () -> Unit,
) {
    val corner by transition.animateDp(
        transitionSpec = { tween(PredictiveBackMillis, easing = WithTheGesture) },
        label = "AppScreenHostCorner",
    ) { state ->
        if (state == EnterExitState.PostExit && gesture.follows) AppMotion.predictiveBackCornerRadius else 0.dp
    }
    val groundColor = MaterialTheme.colorScheme.background
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                if (corner > 0.dp) {
                    shape = RoundedCornerShape(corner)
                    clip = true
                }
            }
            .then(if (onGround) Modifier.background(groundColor).appGroundPattern() else Modifier),
    ) {
        CompositionLocalProvider(LocalAppScreenLeaving provides leaving) {
            content()
        }
    }
}

/**
 * The change an [AppScreenHost] plays for [kind], for a pane that swaps its own pages in an
 * AnimatedContent (a sheet's sub-page) and should move exactly as a screen does.
 */
fun appScreenTransform(
    kind: AppScreenTransition,
    layoutDirection: LayoutDirection,
    density: Density,
): ContentTransform = screenTransform(kind, layoutDirection, density)

/**
 * A change from a settled screen first composes and draws the new screen at the change's start
 * (where it is see-through and the old screen is as it was), and only then plays. The frame that
 * builds a heavy screen (the library, a book) then falls before the change instead of inside it,
 * where it would skip the change's first frames: the old screen gone at once, a bare frame, the
 * new one popping in. An interrupted change, or one a gesture has already started, just goes on.
 */
internal suspend fun <S> SeekableTransitionState<S>.holdUntilDrawn(target: S) {
    if (currentState != targetState || targetState == target) return
    seekTo(0f, targetState = target)
    awaitNewContentDrawn()
}

/** The frame after a change composes, lays out and draws what it brought; the one after that is free. */
internal suspend fun awaitNewContentDrawn() {
    repeat(FramesUntilDrawn) { withFrameNanos { } }
}

private const val FramesUntilDrawn = 2

private fun screenTransform(
    kind: AppScreenTransition,
    layoutDirection: LayoutDirection,
    density: Density,
): ContentTransform {
    // The new screen fades in over the old one; the old one fades out under it once it is mostly covered.
    val fadeIn = fadeIn(AppMotion.pageFadeIn())
    val fadeOut = fadeOut(AppMotion.pageFadeOut())
    val towardEnd = if (layoutDirection == LayoutDirection.Ltr) 1 else -1
    val offset = with(density) { AppMotion.sharedAxisOffset.roundToPx() } * towardEnd
    val slideSpec = AppMotion.pageMove<IntOffset>()
    val (enter, exit) = when (kind) {
        AppScreenTransition.Fade ->
            fadeIn + scaleIn(AppMotion.pageMove(), initialScale = AppMotion.pageInitialScale) to fadeOut

        AppScreenTransition.Forward ->
            slideInHorizontally(slideSpec) { offset } + fadeIn to slideOutHorizontally(slideSpec) { -offset } + fadeOut

        AppScreenTransition.Backward ->
            slideInHorizontally(slideSpec) { -offset } + fadeIn to slideOutHorizontally(slideSpec) { offset } + fadeOut

        AppScreenTransition.None -> EnterTransition.None to ExitTransition.None
    }
    return ContentTransform(targetContentEnter = enter, initialContentExit = exit, sizeTransform = null)
}

/**
 * The change a back gesture scrubs. The gesture's progress covers the first [GestureShare] of
 * it, over which the screen being left (on top) moves, shrinks and rounds in step with the
 * finger; the rest, played once the gesture is let go, fades it out.
 */
private fun predictiveBackTransform(swipeEdge: Int, density: Density): ContentTransform {
    // The screen moves with the finger: right for a swipe from the left edge, left otherwise.
    val direction = if (swipeEdge == BackEventCompat.EDGE_RIGHT) -1 else 1
    val shift = with(density) { AppMotion.predictiveBackShift.roundToPx() } * direction
    val withTheGesture = tween<Float>(PredictiveBackMillis, easing = WithTheGesture)
    val enter = scaleIn(withTheGesture, initialScale = AppMotion.predictiveBackUnderneathScale)
    val exit = slideOutHorizontally(tween(PredictiveBackMillis, easing = WithTheGesture)) { shift } +
        scaleOut(withTheGesture, targetScale = 1f - AppMotion.predictiveBackShrink) +
        fadeOut(tween(PredictiveBackMillis, easing = AfterTheGesture))
    return ContentTransform(
        targetContentEnter = enter,
        initialContentExit = exit,
        targetContentZIndex = -1f,
        sizeTransform = null,
    )
}

/** Runs in step with the gesture and holds once it reaches full progress. */
private val WithTheGesture = Easing { fraction -> (fraction / GestureShare).coerceAtMost(1f) }

/** Waits for the gesture to be let go, then runs over the rest of the change. */
private val AfterTheGesture = Easing { fraction ->
    if (fraction < GestureShare) 0f else (fraction - GestureShare) / (1f - GestureShare)
}

private const val GestureShare = 0.75f
private const val PredictiveBackMillis = 400
