package com.example.unifiedreader.feature.reader.pdf

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.feature.annotations.PageTextRect

/**
 * The words of one page on screen, wherever the page is drawn ([pageBounds], in the page view's pixels): a long
 * press selects, a drag or a handle extends, the menu acts, a tap on a highlight opens its card. The page view
 * puts [placeTracking] and [selecting] on itself and draws [Layer] and [Menu] over its page. Shared by the
 * turning and the scrolling views, which differ only in where their pages are.
 */
@Stable
internal class PdfPageWords(
    val marks: PdfTextMarks,
    private val pageIndex: Int,
    private val pageBounds: () -> Rect,
    private val haptics: HapticFeedback,
) {
    /**
     * Where the page view is, for the highlight card (window pixels) and the toolbar (the root view's pixels).
     * Set on every move, so a toolbar following it moves too; a zoom of a view around it counts.
     */
    private var layout by mutableStateOf<LayoutCoordinates?>(null, neverEqualPolicy())

    fun placeTracking(): Modifier = Modifier.onGloballyPositioned { coordinates -> layout = coordinates }

    /** A tap at [position]: true when it was the selection's or a highlight's, not the page's. */
    fun tap(position: Offset, density: Density): Boolean {
        val page = pageBounds()
        if (page.isEmpty) return false
        val slop = with(density) { TapSlop.toPx() } / maxOf(page.width, page.height)
        return marks.tap(pageIndex, screenToPage(page, position), slop) { mark -> onScreen(mark.spot.rects)?.placed() }
    }

    /**
     * Taps for a page view whose own tap handling is elsewhere (the scrolling strip's): one on the selection or a
     * highlight is used up here, so the strip doesn't also show its bars.
     */
    fun tapping(): Modifier = Modifier.pointerInput(this) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            val up = waitForUpOrCancellation()
            if (up != null && tap(up.position, this)) up.consume()
        }
    }

    /** Long press, drag and handles, on the page view. */
    fun selecting(): Modifier = Modifier.pointerInput(this) {
        val radius = HandleRadius.toPx()
        val handleReach = HandleReach.toPx()
        val wordReach = WordReach.toPx()
        // From the finger to the text point of the handle it holds, so the text under the finger isn't hidden.
        var fingerToText = Offset.Zero
        detectPdfTextPress(
            onHandle = { position ->
                val grabbed = grabHandle(position, radius, handleReach)
                fingerToText = grabbed ?: Offset.Zero
                grabbed != null
            },
            onLongPress = { position ->
                fingerToText = Offset.Zero
                val page = pageBounds()
                if (!page.isEmpty) {
                    marks.press(pageIndex, screenToPage(page, position), reach = wordReach / page.width) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                }
            },
            onDrag = { position -> marks.drag(screenToPage(pageBounds(), position + fingerToText)) },
            onRelease = marks::release,
        )
    }

    /** The page's highlights and selection. */
    @Composable
    fun Layer(modifier: Modifier = Modifier) {
        PdfPageMarksLayer(marks = marks, pageIndex = pageIndex, pageBounds = pageBounds, modifier = modifier)
    }

    /** The selection's Highlight / Note / Dictionary / Copy toolbar, while this page has the selection. */
    @Composable
    fun Menu() {
        val context = LocalContext.current
        if (marks.selection?.pageIndex != pageIndex) return
        PdfSelectionMenu(
            marks = marks,
            bounds = { selectionOnScreen()?.placed(toRoot = true) },
            hasDictionary = marks.dictionary != null,
            onAction = { action ->
                when (action) {
                    PdfSelectionAction.Highlight -> marks.highlightSelection(selectionOnScreen()?.placed(), withNote = false)
                    PdfSelectionAction.Note -> marks.highlightSelection(selectionOnScreen()?.placed(), withNote = true)
                    PdfSelectionAction.Dictionary -> {
                        marks.selection?.let { selection -> marks.dictionary?.lookUp(selection) }
                        marks.clear()
                    }
                    PdfSelectionAction.Copy -> marks.copySelection(context)
                }
            },
        )
    }

    /** Takes the handle at [position] if there is one: the offset from the finger to its text point, or null. */
    private fun grabHandle(position: Offset, radius: Float, reach: Float): Offset? {
        val selection = marks.selection?.takeIf { it.pageIndex == pageIndex && it.dragging == null }
        val page = pageBounds()
        val handle = selection?.let { pdfSelectionHandles(it, page)?.at(position, radius, reach) }
        val textPoint = when (handle) {
            PdfSelectionDrag.Start -> selection?.startPoint
            PdfSelectionDrag.End -> selection?.endPoint
            else -> null
        }
        if (handle == null || textPoint == null) return null
        marks.grab(handle)
        return pageToScreen(page, textPoint.x, textPoint.y) - position
    }

    private fun selectionOnScreen(): Rect? =
        marks.selection?.takeIf { it.pageIndex == pageIndex }?.let { selection -> onScreen(selection.rects) }

    /** [this] in the window's pixels, or with [toRoot] the root view's. */
    private fun Rect.placed(toRoot: Boolean = false): Rect? = layout?.takeIf { it.isAttached }?.let { at ->
        if (toRoot) {
            Rect(at.localToRoot(topLeft), at.localToRoot(bottomRight))
        } else {
            Rect(at.localToWindow(topLeft), at.localToWindow(bottomRight))
        }
    }

    /** The box around [rects] on screen, in the page view's pixels. */
    private fun onScreen(rects: List<PageTextRect>): Rect? {
        val page = pageBounds()
        return rects.map { rect -> pageRectToScreen(page, rect) }.reduceOrNull { a, b ->
            Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
        }
    }
}

/** The words of page [pageIndex] of [marks], drawn at [pageBounds]; kept while the page stays. */
@Composable
internal fun rememberPdfPageWords(
    marks: PdfTextMarks,
    pageIndex: Int,
    pageBounds: () -> Rect,
): PdfPageWords {
    val haptics = LocalHapticFeedback.current
    val latestBounds: State<() -> Rect> = rememberUpdatedState(pageBounds)
    return remember(marks, pageIndex, haptics) {
        PdfPageWords(marks = marks, pageIndex = pageIndex, pageBounds = { latestBounds.value() }, haptics = haptics)
    }
}

/** How far from a highlight's words a tap still opens it. */
private val TapSlop = 6.dp

/** How far from a word a long press still selects it: a press between two lines takes the nearer. */
private val WordReach = 24.dp
