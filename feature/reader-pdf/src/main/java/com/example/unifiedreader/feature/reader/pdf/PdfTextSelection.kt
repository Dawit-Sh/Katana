package com.example.unifiedreader.feature.reader.pdf

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import com.example.unifiedreader.feature.annotations.PageTextRect

/** What the selection's menu asks for. */
internal enum class PdfSelectionAction { Highlight, Note, Dictionary, Copy }

/** What a finger on a selection is moving: the words it grew from (after the long press), or one of its ends. */
internal enum class PdfSelectionDrag { Words, Start, End }

/**
 * Selected words on one PDF page: [page]'s characters [start] up to [end] (not included). [anchor] is the word the
 * long press chose; dragging after the press keeps it and adds whole words on the side the finger goes. [dragging]
 * says what a finger is moving now; the selection's menu shows only once it is null.
 */
@Immutable
internal class PdfTextSelection private constructor(
    val page: PdfPageText,
    val start: Int,
    val end: Int,
    private val anchor: IntRange,
    val dragging: PdfSelectionDrag?,
) {
    val pageIndex: Int get() = page.pageIndex

    /** One box per line, as fractions of the page. */
    val rects: List<PageTextRect> = page.rects(start, end)

    /** The words as they read, lines joined. */
    val text: String get() = page.textOf(start, end)

    private val ends: PdfTextEnds? = page.ends(start, end)

    /** Where the start handle points: the selection's first line, at its start, half way across. */
    val startPoint: Offset? get() = ends?.startPoint

    /** Where the end handle points: the selection's last line, at its end, half way across. */
    val endPoint: Offset? get() = ends?.endPoint

    /** The corner the start handle hangs from: the first line's start, at its foot (its left, below it, upright). */
    val startCorner: Offset? get() = ends?.startCorner

    /** The corner the end handle hangs from: the last line's end, at its foot. */
    val endCorner: Offset? get() = ends?.endCorner

    /** The selection with a finger now at character [index], moving what [dragging] says. */
    fun dragTo(index: Int): PdfTextSelection {
        if (index !in 0 until page.length) return this
        return when (dragging) {
            PdfSelectionDrag.Words -> {
                val word = page.wordAt(index)
                when {
                    word.first < anchor.first -> copy(start = word.first, end = anchor.last + 1)
                    word.last > anchor.last -> copy(start = anchor.first, end = word.last + 1)
                    else -> copy(start = anchor.first, end = anchor.last + 1)
                }
            }
            PdfSelectionDrag.Start -> copy(start = index.coerceAtMost(end - 1))
            PdfSelectionDrag.End -> copy(end = (index + 1).coerceAtLeast(start + 1))
            null -> this
        }
    }

    /** The same words, with a finger now moving [drag] (or none). */
    fun withDrag(drag: PdfSelectionDrag?): PdfTextSelection = copy(dragging = drag)

    fun sameWords(other: PdfTextSelection?): Boolean =
        other != null && other.pageIndex == pageIndex && other.start == start && other.end == end

    private fun copy(
        start: Int = this.start,
        end: Int = this.end,
        dragging: PdfSelectionDrag? = this.dragging,
    ): PdfTextSelection = PdfTextSelection(page, start, end, anchor, dragging)

    companion object {
        /**
         * The word nearest to [point] (page fractions) on [page], within [reach] page widths, with the finger still
         * down; null where there is no text that close (a picture, a margin, a scanned page).
         */
        fun at(page: PdfPageText, point: Offset, reach: Float): PdfTextSelection? =
            page.charNear(point.x, point.y, reach)
                ?.let(page::wordAt)
                ?.takeUnless(IntRange::isEmpty)
                ?.let { word -> PdfTextSelection(page, word.first, word.last + 1, word, PdfSelectionDrag.Words) }
                ?.takeIf { it.rects.isNotEmpty() }
    }
}
