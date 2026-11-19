package com.example.unifiedreader.feature.reader.pdf

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.tween
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Pinch zoom over the scrolling page list. The whole list is scaled around the view's centre and moved by
 * [offsetX] and [offsetY]; up and down beyond what the offset allows, a zoomed drag scrolls the list itself
 * ([pdfZoomedVerticalPan]), so a zoomed page can still be read from the top of the book to its end. At
 * [MinimumScale] the list scrolls by itself and this does nothing.
 */
@Stable
internal class PdfScrollZoomState(
    private val listState: LazyListState,
) {
    private var animationJob: Job? = null

    var scale by mutableFloatStateOf(MinimumScale)
        private set

    var offsetX by mutableFloatStateOf(0f)
        private set

    var offsetY by mutableFloatStateOf(0f)
        private set

    var viewportSize by mutableStateOf(IntSize.Zero)

    /** Whether the reader has touched the pages yet: until then, a move is the book opening where it was. */
    var touched by mutableStateOf(false)

    val isZoomed: Boolean
        get() = scale > MinimumScale + ZoomEpsilon

    /** The list pixel (from the viewport's top) under the middle of the screen. */
    val focusListY: Float
        get() = viewportSize.height / 2f - offsetY / scale

    /** A pinch: [zoomChange] around [centroid] (screen pixels), with [pan] for the fingers' move. */
    fun onTransform(centroid: Offset, pan: Offset, zoomChange: Float) {
        stopAnimation()
        zoomAround(scale * zoomChange, centroid)
        panBy(pan)
    }

    /** A one-finger drag while zoomed; returns how much of [pan] was used. */
    fun onPan(pan: Offset): Offset {
        val beforeX = offsetX
        val beforeY = offsetY
        val beforeFirst = listState.firstVisibleItemIndex
        val beforeScroll = listState.firstVisibleItemScrollOffset
        panBy(pan)
        val listMoved = beforeFirst != listState.firstVisibleItemIndex || beforeScroll != listState.firstVisibleItemScrollOffset
        return Offset(
            x = offsetX - beforeX,
            y = if (listMoved) pan.y else offsetY - beforeY,
        )
    }

    fun launchFling(scope: CoroutineScope, velocity: Offset, density: Density) {
        if (!isZoomed || velocity == Offset.Zero) return
        stopAnimation()
        animationJob = scope.launch {
            var previous = Offset.Zero
            AnimationState(
                typeConverter = Offset.VectorConverter,
                initialValue = Offset.Zero,
                initialVelocity = velocity,
            ).animateDecay(splineBasedDecay(density)) {
                val used = onPan(value - previous)
                previous = value
                if (abs(used.x) < PanEpsilon && abs(used.y) < PanEpsilon) cancelAnimation()
            }
        }
    }

    /** Double tap: zoom in on [focal], or back out to the page width. */
    fun animateToggle(scope: CoroutineScope, focal: Offset) {
        val target = if (isZoomed) MinimumScale else DoubleTapScale
        stopAnimation()
        animationJob = scope.launch {
            val start = scale
            var previousScale = start
            AnimationState(initialValue = 0f).animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = ZoomAnimationMillis, easing = FastOutSlowInEasing),
            ) {
                val next = start + (target - start) * value
                zoomAround(next, focal.takeIf { target > MinimumScale } ?: viewportCenter)
                previousScale = next
            }
            if (previousScale <= MinimumScale + ZoomEpsilon) reset()
        }
    }

    /** Back to the page width, keeping the place: whatever the offset showed moves into the list's scroll. */
    fun reset() {
        stopAnimation()
        zoomAround(MinimumScale, viewportCenter)
        offsetX = 0f
        offsetY = 0f
    }

    fun stopAnimation() {
        animationJob?.cancel()
        animationJob = null
    }

    private fun zoomAround(requestedScale: Float, focal: Offset) {
        val newScale = requestedScale.coerceIn(MinimumScale, MaximumScale)
        val center = viewportCenter
        val oldScale = scale
        scale = newScale
        offsetX = pdfFocalOffset(offsetX, oldScale, newScale, focal.x - center.x)
            .coerceIn(-maxOffsetX, maxOffsetX)
        // Up and down, what the offset can't hold scrolls the list, so the point under the fingers stays put.
        val wantedY = pdfFocalOffset(offsetY, oldScale, newScale, focal.y - center.y)
        val heldY = wantedY.coerceIn(-maxOffsetY, maxOffsetY)
        val overflow = wantedY - heldY
        if (overflow != 0f) listState.dispatchRawDelta(-overflow / newScale)
        offsetY = heldY
    }

    private fun panBy(pan: Offset) {
        offsetX = (offsetX + pan.x).coerceIn(-maxOffsetX, maxOffsetX)
        offsetY = pdfZoomedVerticalPan(
            dy = pan.y,
            scale = scale,
            offsetY = offsetY,
            maxOffsetY = maxOffsetY,
            scrollList = listState::dispatchRawDelta,
        )
    }

    private val maxOffsetX: Float
        get() = pdfMaxZoomOffset(viewportSize.width, scale)

    private val maxOffsetY: Float
        get() = pdfMaxZoomOffset(viewportSize.height, scale)

    private val viewportCenter: Offset
        get() = Offset(viewportSize.width / 2f, viewportSize.height / 2f)

    companion object {
        const val MinimumScale = 1f
        const val MaximumScale = 4f
        const val DoubleTapScale = 2.5f
        private const val ZoomEpsilon = 0.01f
        private const val PanEpsilon = 0.5f
        private const val ZoomAnimationMillis = 220
    }
}

/**
 * Moves a zoomed view by a vertical finger move [dy] (screen pixels, positive when the page goes down) and
 * returns the zoom's new vertical offset. The view moves by its own offset first when that offset is on
 * the side the finger leaves, then by scrolling the list ([scrollList] takes list pixels, positive toward the
 * end, and returns how many it moved), and whatever the list can't take (at either end of the book) moves
 * the offset, within [maxOffsetY]. So the page always follows the finger, and the zoomed view still reaches
 * the top of the first page and the bottom of the last.
 */
internal fun pdfZoomedVerticalPan(
    dy: Float,
    scale: Float,
    offsetY: Float,
    maxOffsetY: Float,
    scrollList: (Float) -> Float,
): Float {
    var remaining = dy
    var offset = offsetY
    if (offset > 0f && remaining < 0f) {
        val taken = max(remaining, -offset)
        offset += taken
        remaining -= taken
    } else if (offset < 0f && remaining > 0f) {
        val taken = min(remaining, -offset)
        offset += taken
        remaining -= taken
    }
    if (remaining != 0f && scale > 0f) {
        val moved = scrollList(-remaining / scale)
        remaining += moved * scale
    }
    if (remaining != 0f) {
        offset = (offset + remaining).coerceIn(-maxOffsetY, maxOffsetY)
    }
    return offset
}

/**
 * The offset that keeps the point under [focal] (its distance from the view's centre) still while the zoom
 * goes from [oldScale] to [newScale].
 */
internal fun pdfFocalOffset(
    offset: Float,
    oldScale: Float,
    newScale: Float,
    focal: Float,
): Float {
    if (oldScale <= 0f) return offset
    val ratio = newScale / oldScale
    return offset * ratio + focal * (1f - ratio)
}

/** How far a zoomed view may move from the centre along an axis [viewportPx] long. */
internal fun pdfMaxZoomOffset(viewportPx: Int, scale: Float): Float = (viewportPx * (scale - 1f) / 2f).coerceAtLeast(0f)
