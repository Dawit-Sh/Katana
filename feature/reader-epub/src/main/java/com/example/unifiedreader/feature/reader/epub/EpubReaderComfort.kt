package com.example.unifiedreader.feature.reader.epub

import android.app.Activity
import android.content.Context
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.unifiedreader.feature.reader.preferences.ReaderComfort
import com.example.unifiedreader.feature.reader.preferences.ReaderComfortPreferencesStore
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * The reader's light and screen (the Light card, the left-edge swipe): the saved choices in [comfort],
 * ahead of the store while a slider or a swipe is moving ([preview], saved by [commit]), and when the
 * page was last touched, which keeps the screen on for a while.
 */
@Stable
internal class EpubReaderComfort(
    private val store: ReaderComfortPreferencesStore,
    private val saved: State<ReaderComfort>,
    private val tabletBrightness: () -> Float,
) {
    /** What the reader shows now but the store doesn't have yet. */
    private var pending by mutableStateOf<ReaderComfort?>(null)

    val comfort: ReaderComfort get() = pending ?: saved.value

    /** A finger is sliding along the left edge: the indicator shows. */
    var isSwiping by mutableStateOf(false)
        private set

    internal val interactions = MutableStateFlow(0L)

    /** The level the reader's window has, or null for the tablet's. */
    val windowBrightness: Float?
        get() = comfort.takeIf { it.ownBrightness }?.brightness?.let(::clampEpubBrightness)

    /** The level to show in the card and by the edge: the tablet's while it is followed. */
    val shownBrightness: Float
        get() = windowBrightness ?: tabletBrightness()

    /** The page was touched, turned or keyed: the screen stays on for [EpubKeepScreenOnIdleMillis] more. */
    fun touched() {
        interactions.value++
    }

    /** Shows [transform]'s result at once, without saving it: a slider or a swipe on its way. */
    fun preview(transform: (ReaderComfort) -> ReaderComfort) {
        pending = transform(comfort)
    }

    /** Saves what [preview] shows. */
    fun commit() {
        val next = pending ?: return
        store.update { next }
    }

    /** The store has caught up with what was shown. */
    internal fun onSaved(value: ReaderComfort) {
        if (value == pending) pending = null
    }

    fun beginBrightnessSwipe(): Float {
        isSwiping = true
        return shownBrightness
    }

    /** The swipe's level becomes the reader's own. */
    fun endBrightnessSwipe() {
        isSwiping = false
        commit()
    }

    fun previewBrightness(level: Float) = preview { it.copy(ownBrightness = true, brightness = clampEpubBrightness(level)) }

    fun followTablet(follow: Boolean) {
        val start = shownBrightness
        change { it.copy(ownBrightness = !follow, brightness = it.brightness ?: start) }
    }

    fun previewWarmth(level: Float) = preview { it.copy(warmth = level.coerceIn(0f, 1f)) }

    /** Shows and saves [transform]'s result: a switch. */
    fun change(transform: (ReaderComfort) -> ReaderComfort) {
        preview(transform)
        commit()
    }
}

@Composable
internal fun rememberEpubReaderComfort(store: ReaderComfortPreferencesStore): EpubReaderComfort {
    val context = LocalContext.current
    val saved = store.comfortFlow.collectAsStateWithLifecycle()
    val comfort = remember(store, saved, context) {
        EpubReaderComfort(store = store, saved = saved, tabletBrightness = { context.tabletBrightness() })
    }
    LaunchedEffect(comfort) { store.comfortFlow.collect(comfort::onSaved) }
    return comfort
}

/**
 * What the comfort settings do to the reader's window while a book is open: its own brightness
 * (`screenBrightness`, never the tablet's setting) and keeping the screen on until
 * [EpubKeepScreenOnIdleMillis] pass without a touch. Leaving the book gives both back.
 */
@Composable
internal fun EpubReaderComfortEffects(
    comfort: EpubReaderComfort,
    activity: Activity,
) {
    val window = activity.window
    val brightness = comfort.windowBrightness
    LaunchedEffect(window, brightness) {
        window.attributes = window.attributes.apply {
            screenBrightness = brightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }
    val keepScreenOn = comfort.comfort.keepScreenOn
    LaunchedEffect(window, keepScreenOn) {
        if (!keepScreenOn) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            return@LaunchedEffect
        }
        comfort.interactions.collectLatest {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            delay(EpubKeepScreenOnIdleMillis)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    DisposableEffect(window) {
        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }
}

/**
 * Brightness by sliding a finger up or down the left edge of the page. A drag that starts within
 * [EpubBrightnessEdgeWidth] of the left edge and goes up or down is the brightness's: the page
 * (Readium, the page curl) gets a cancel. A tap, a sideways swipe or a long press there stays the
 * page's. Every touch on the page also counts as reading for Keep the screen on.
 */
internal fun Modifier.epubBrightnessEdgeSwipe(comfort: EpubReaderComfort): Modifier = pointerInput(comfort) {
    val edge = EpubBrightnessEdgeWidth.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        comfort.touched()
        val start = if (down.position.x <= edge) awaitEpubBrightnessSwipeStart(down) else null
        if (start != null) followEpubBrightnessSwipe(start, comfort, travel = size.height * EpubBrightnessSwipeTravel)
    }
}

/** What a touch at the left edge turns out to be, from how far it has moved and for how long. */
internal enum class EpubEdgeTouch {
    Undecided,
    Brightness,
    Page,
}

/**
 * Up or down past the touch slop is the brightness's; sideways, a second finger or a lift (not
 * [oneFingerDown]) or a long press (selecting text) stays the page's.
 */
internal fun resolveEpubEdgeTouch(
    movement: Offset,
    elapsedMillis: Long,
    oneFingerDown: Boolean,
    touchSlop: Float,
    longPressMillis: Long,
): EpubEdgeTouch {
    val x = abs(movement.x)
    val y = abs(movement.y)
    return when {
        !oneFingerDown -> EpubEdgeTouch.Page
        elapsedMillis > longPressMillis && movement.getDistance() <= touchSlop -> EpubEdgeTouch.Page
        x > touchSlop && x >= y -> EpubEdgeTouch.Page
        y > touchSlop && y > x * VerticalBias -> EpubEdgeTouch.Brightness
        else -> EpubEdgeTouch.Undecided
    }
}

/** Watches the touch until it shows what it is: the change that made it a brightness swipe, or null. */
private suspend fun AwaitPointerEventScope.awaitEpubBrightnessSwipeStart(down: PointerInputChange): PointerInputChange? {
    var touch = EpubEdgeTouch.Undecided
    var last: PointerInputChange? = null
    while (touch == EpubEdgeTouch.Undecided) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == down.id }
        touch = if (change == null || change.isConsumed) {
            EpubEdgeTouch.Page
        } else {
            resolveEpubEdgeTouch(
                movement = change.position - down.position,
                elapsedMillis = change.uptimeMillis - down.uptimeMillis,
                oneFingerDown = change.pressed && event.changes.count { it.pressed } == 1,
                touchSlop = viewConfiguration.touchSlop,
                longPressMillis = viewConfiguration.longPressTimeoutMillis,
            )
        }
        last = change
    }
    return last.takeIf { touch == EpubEdgeTouch.Brightness }
}

/** Sets the brightness as the finger moves, [travel] pixels from darkest to brightest, until it lifts. */
private suspend fun AwaitPointerEventScope.followEpubBrightnessSwipe(
    start: PointerInputChange,
    comfort: EpubReaderComfort,
    travel: Float,
) {
    val startLevel = comfort.beginBrightnessSwipe()
    start.consume()
    try {
        var pressed = true
        while (pressed) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
            val change = event.changes.firstOrNull { it.id == start.id }
            change?.let { comfort.previewBrightness(startLevel + (start.position.y - it.position.y) / travel) }
            pressed = change?.pressed == true
        }
    } finally {
        comfort.endBrightnessSwipe()
    }
}

/** The window's own level stays above black: 0 turns some backlights off. */
internal fun clampEpubBrightness(level: Float): Float = level.coerceIn(EpubMinBrightness, 1f)

/** "40%". */
internal fun epubBrightnessLabel(level: Float): String = "${Math.round(level.coerceIn(0f, 1f) * Percent)}%"

/** The tablet's brightness setting, 0 to 1 (its slider's value, which auto-brightness may not be showing). */
private fun Context.tabletBrightness(): Float {
    val value = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
    return if (value < 0) DefaultTabletBrightness else (value / MaxBrightnessSetting).coerceIn(0f, 1f)
}

/** From the left edge, how wide the strip is that a brightness swipe starts in. */
internal val EpubBrightnessEdgeWidth = 36.dp

/** A swipe this share of the page's height goes from darkest to brightest. */
private const val EpubBrightnessSwipeTravel = 0.6f
private const val VerticalBias = 1.5f
private const val EpubMinBrightness = 0.02f
private const val DefaultTabletBrightness = 0.5f
private const val MaxBrightnessSetting = 255f
private const val Percent = 100f

/** After this long without a touch, a page turn or a key, the screen may sleep again. */
internal const val EpubKeepScreenOnIdleMillis = 10 * 60 * 1000L
