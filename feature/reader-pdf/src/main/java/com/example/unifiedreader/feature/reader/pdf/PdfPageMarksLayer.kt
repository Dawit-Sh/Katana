package com.example.unifiedreader.feature.reader.pdf

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.feature.annotations.HighlightStyle
import com.example.unifiedreader.feature.annotations.PageTextRect
import com.example.unifiedreader.feature.reader.core.ReaderSearchTint
import com.example.unifiedreader.feature.reader.core.ReaderSelectionHighlight
import kotlin.math.max

/**
 * Page [pageIndex]'s highlights in their style and colour, and its selected words with their two handles, drawn
 * over the page image wherever [pageBounds] says the page is on screen now (zoomed, moved, or one of several in a
 * scrolling strip). Reading the bounds here redraws with every zoom step without recomposing.
 */
@Composable
internal fun PdfPageMarksLayer(
    marks: PdfTextMarks,
    pageIndex: Int,
    pageBounds: () -> Rect,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val page = pageBounds()
        if (page.isEmpty) return@Canvas
        marks.search.hitsOn(pageIndex).forEach { (hit, current) -> drawSearchHit(hit.rects, page, current) }
        marks.highlightsOn(pageIndex).forEach { mark ->
            drawHighlight(
                rects = mark.spot.rects,
                page = page,
                style = mark.highlight.style,
                color = Color(mark.highlight.color.argb),
                fillAlpha = mark.highlight.color.fillAlpha,
            )
        }
        marks.selection?.takeIf { it.pageIndex == pageIndex }?.let { selection ->
            selection.rects.forEach { rect ->
                val onScreen = pageRectToScreen(page, rect)
                drawRect(ReaderSelectionHighlight.color, onScreen.topLeft, onScreen.size)
            }
            pdfSelectionHandles(selection, page)?.let { handles ->
                drawHandle(handles.start, PdfSelectionDrag.Start, HandleRadius.toPx())
                drawHandle(handles.end, PdfSelectionDrag.End, HandleRadius.toPx())
            }
        }
    }
}

/** Where a selection's handles hang on screen: under its first line's start and its last line's end. */
internal class PdfSelectionHandles(val start: Offset, val end: Offset) {
    /** The handle a finger down at [position] takes, within [reach] pixels of its round part; null for neither. */
    fun at(position: Offset, radius: Float, reach: Float): PdfSelectionDrag? {
        val startCentre = handleCentre(start, PdfSelectionDrag.Start, radius)
        val endCentre = handleCentre(end, PdfSelectionDrag.End, radius)
        val toStart = (position - startCentre).getDistance()
        val toEnd = (position - endCentre).getDistance()
        return when {
            toStart > reach && toEnd > reach -> null
            toStart <= toEnd -> PdfSelectionDrag.Start
            else -> PdfSelectionDrag.End
        }
    }
}

/** The handles of [selection] for a page drawn at [page], or null when it has no lines. */
internal fun pdfSelectionHandles(selection: PdfTextSelection, page: Rect): PdfSelectionHandles? {
    val first = selection.rects.firstOrNull() ?: return null
    val last = selection.rects.last()
    return PdfSelectionHandles(
        start = pageToScreen(page, first.left, first.bottom),
        end = pageToScreen(page, last.right, last.bottom),
    )
}

/** A handle's round part: below the line, to the left of the start's corner and to the right of the end's. */
private fun handleCentre(corner: Offset, handle: PdfSelectionDrag, radius: Float): Offset =
    Offset(if (handle == PdfSelectionDrag.Start) corner.x - radius else corner.x + radius, corner.y + radius)

/** A teardrop whose sharp corner touches the selection's corner, as Android's own text handles. */
private fun DrawScope.drawHandle(corner: Offset, handle: PdfSelectionDrag, radius: Float) {
    val colour = ReaderSelectionHighlight.color.copy(alpha = 1f)
    val centre = handleCentre(corner, handle, radius)
    drawCircle(colour, radius, centre)
    val squareLeft = if (handle == PdfSelectionDrag.Start) corner.x - radius else corner.x
    drawRect(colour, Offset(squareLeft, corner.y), Size(radius, radius))
}

/** A search match: the amber behind its words, stronger with a line under them when it is the [current] one. */
private fun DrawScope.drawSearchHit(rects: List<PageTextRect>, page: Rect, current: Boolean) {
    rects.forEach { rect ->
        val line = pageRectToScreen(page, rect)
        drawRect(ReaderSearchTint.copy(alpha = if (current) CurrentHitAlpha else OtherHitAlpha), line.topLeft, line.size)
        if (current) {
            val stroke = MinLine.toPx()
            drawLine(ReaderSearchTint, Offset(line.left, line.bottom - stroke / 2f), Offset(line.right, line.bottom - stroke / 2f), stroke)
        }
    }
}

/** One highlight's lines on a page drawn at [page]: filled, underlined, struck through or with a wavy line. */
private fun DrawScope.drawHighlight(
    rects: List<PageTextRect>,
    page: Rect,
    style: HighlightStyle,
    color: Color,
    fillAlpha: Float,
) {
    rects.forEach { rect ->
        val line = pageRectToScreen(page, rect)
        val stroke = max(MinLine.toPx(), line.height * LineShare)
        when (style) {
            HighlightStyle.Highlight -> drawRect(color.copy(alpha = fillAlpha), line.topLeft, line.size)
            HighlightStyle.Underline -> {
                val y = line.bottom - stroke / 2f
                drawLine(color, Offset(line.left, y), Offset(line.right, y), stroke)
            }
            HighlightStyle.Strikethrough -> {
                val y = line.center.y
                drawLine(color, Offset(line.left, y), Offset(line.right, y), stroke)
            }
            HighlightStyle.Squiggly -> drawPath(squiggle(line, stroke), color, style = Stroke(width = stroke))
        }
    }
}

/** A wave along the bottom of [line], as the EPUB reader's (a crest every six strokes, about a stroke high). */
private fun squiggle(line: Rect, stroke: Float): Path {
    val wave = stroke * WaveLength
    val height = stroke * WaveHeight
    // The wave's middle; its troughs touch the bottom of the line, less half the stroke.
    val middle = line.bottom - stroke / 2f - height
    return Path().apply {
        moveTo(line.left, middle)
        var x = line.left
        var up = true
        while (x < line.right) {
            val next = minOf(x + wave / 2f, line.right)
            // A quadratic's control point twice as far out as the crest it makes.
            quadraticTo((x + next) / 2f, if (up) middle - height * 2f else middle + height * 2f, next, middle)
            x = next
            up = !up
        }
    }
}

/**
 * Selecting words on a page: a finger held still for the long-press time selects the word under it and, without
 * lifting, drags the selection on by words ([onLongPress], then [onDrag]); a finger put down on a selection's
 * handle drags that end ([onHandle] says whether it is one). Every touch of a selection is used up here, so the
 * page doesn't turn, pan or show its bars under it; a tap or a pan that starts as one is left to the page.
 */
internal suspend fun PointerInputScope.detectPdfTextPress(
    onHandle: (Offset) -> Boolean,
    onLongPress: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onRelease: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val held = when {
            onHandle(down.position) -> {
                down.consume()
                down.position
            }
            else -> awaitStillPress(down.id, down.position)?.also(onLongPress)
        }
        if (held != null) {
            followFinger(down.id, onDrag)
            onRelease()
        }
    }
}

/** Where the finger is once it has stayed down, unmoved and alone, for the long-press time; null if it didn't. */
private suspend fun AwaitPointerEventScope.awaitStillPress(id: PointerId, start: Offset): Offset? {
    val slop = viewConfiguration.touchSlop
    var position = start
    val timedOut = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        var still = true
        while (still) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == id }
            val others = event.changes.count { it.pressed && it.id != id }
            still = change != null && change.pressed && others == 0 && (change.position - start).getDistance() <= slop
            change?.let { position = it.position }
        }
        false
    }
    return if (timedOut == null) position else null
}

/** Follows the finger until it lifts, using up every move and the lift. */
private suspend fun AwaitPointerEventScope.followFinger(id: PointerId, onDrag: (Offset) -> Unit) {
    var down = true
    while (down) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == id }
        down = change != null && change.pressed
        if (change != null) {
            if (down && change.positionChanged()) onDrag(change.position)
            change.consume()
        }
    }
}

/** A handle's round part. */
internal val HandleRadius: Dp = 10.dp

/** How far from a handle's round part a finger still takes it. */
internal val HandleReach: Dp = 28.dp

private val MinLine: Dp = 1.5.dp
private const val LineShare = 0.07f
private const val WaveLength = 6f
private const val WaveHeight = 0.55f

// As the EPUB reader tints its matches.
private const val CurrentHitAlpha = 0.55f
private const val OtherHitAlpha = 0.22f
