package com.example.unifiedreader.feature.reader.pdf

import androidx.compose.ui.geometry.Offset
import com.example.unifiedreader.feature.annotations.PageTextRect
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/*
 * Words on a PDF page don't always run left to right across the drawn page: a page turned by its /Rotate, or a
 * table printed sideways, has them running down, up or upside down. [PdfPageText] works out lines, the nearest
 * word and a selection's ends as the words read ("reading" fractions: along a line left to right, lines going
 * down) and turns the results back onto the drawn page (fractions from its top left corner). A turn is counted
 * in quarter turns clockwise: 0 left to right, 1 running down, 2 upside down, 3 running up.
 */

/** The number of ways words can run: right, down, left, up. */
internal const val QuarterTurns = 4

/** [turn] as 0 to 3. */
internal fun quarterTurn(turn: Int): Int = ((turn % QuarterTurns) + QuarterTurns) % QuarterTurns

/**
 * How most words on a page run, from where each letter sits after the one before it in the same word ([boxes] as
 * drawn, four numbers a character). Words running right to left on a page that isn't itself upside down
 * ([pageTurn] other than 2) are Hebrew or Arabic, not turned, so they count as 0. A page with no two letters side
 * by side takes the page's own turn.
 */
internal fun readingTurn(text: String, boxes: FloatArray, aspect: Float, pageTurn: Int): Int {
    val votes = IntArray(QuarterTurns)
    for (index in 0 until text.length - 1) {
        letterStep(text, boxes, index, aspect)?.let { votes[it]++ }
    }
    val most = votes.indices.maxBy { votes[it] }
    val page = quarterTurn(pageTurn)
    return when {
        votes[most] == 0 -> page
        most == UpsideDown && page != UpsideDown -> 0
        else -> most
    }
}

/** Which way the letter after [index] sits from it, when both are letters or digits with boxes; null otherwise. */
private fun letterStep(text: String, boxes: FloatArray, index: Int, aspect: Float): Int? {
    if (!text[index].isLetterOrDigit() || !text[index + 1].isLetterOrDigit()) return null
    val at = index * PdfPageText.BoxSize
    val next = at + PdfPageText.BoxSize
    val dx = centre(boxes, next, PdfPageText.Left, PdfPageText.Right) - centre(boxes, at, PdfPageText.Left, PdfPageText.Right)
    val dy = (centre(boxes, next, PdfPageText.Top, PdfPageText.Bottom) - centre(boxes, at, PdfPageText.Top, PdfPageText.Bottom)) * aspect
    return when {
        !(dx.isFinite() && dy.isFinite()) || hypot(dx, dy) < MinStep -> null
        abs(dx) >= abs(dy) -> if (dx > 0f) 0 else UpsideDown
        else -> if (dy > 0f) 1 else Up
    }
}

private fun centre(boxes: FloatArray, at: Int, first: Int, second: Int): Float = (boxes[at + first] + boxes[at + second]) / 2f

/** The drawn page's point ([x], [y]) in reading fractions for words that run [turn]. */
internal fun toReading(x: Float, y: Float, turn: Int): Offset = when (quarterTurn(turn)) {
    1 -> Offset(y, 1f - x)
    UpsideDown -> Offset(1f - x, 1f - y)
    Up -> Offset(1f - y, x)
    else -> Offset(x, y)
}

/** The reading point ([along], [across]) on the drawn page, for words that run [turn]. */
internal fun fromReading(along: Float, across: Float, turn: Int): Offset = when (quarterTurn(turn)) {
    1 -> Offset(1f - across, along)
    UpsideDown -> Offset(1f - along, 1f - across)
    Up -> Offset(across, 1f - along)
    else -> Offset(along, across)
}

/** A line's box in reading fractions, on the drawn page. */
internal fun drawn(rect: PageTextRect, turn: Int): PageTextRect {
    if (quarterTurn(turn) == 0) return rect
    val a = fromReading(rect.left, rect.top, turn)
    val b = fromReading(rect.right, rect.bottom, turn)
    return PageTextRect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
}

/** Turns the drawn box at [at] of [boxes] into reading fractions ([toReading]), in place. */
internal fun turnBoxToReading(boxes: FloatArray, at: Int, turn: Int) {
    val left = boxes[at + PdfPageText.Left]
    if (left.isNaN()) return
    val a = toReading(left, boxes[at + PdfPageText.Top], turn)
    val b = toReading(boxes[at + PdfPageText.Right], boxes[at + PdfPageText.Bottom], turn)
    boxes[at + PdfPageText.Left] = min(a.x, b.x)
    boxes[at + PdfPageText.Top] = min(a.y, b.y)
    boxes[at + PdfPageText.Right] = max(a.x, b.x)
    boxes[at + PdfPageText.Bottom] = max(a.y, b.y)
}

/** Which way, on screen, a line of words running [turn] goes (a unit step). */
internal fun alongLine(turn: Int): Offset = fromReading(1f, 0f, turn) - fromReading(0f, 0f, turn)

/** Which way, on screen, the next line of words running [turn] is (a unit step). */
internal fun acrossLines(turn: Int): Offset = fromReading(0f, 1f, turn) - fromReading(0f, 0f, turn)


private const val UpsideDown = 2
private const val Up = 3

/** Letters closer than this (in page widths) say nothing about which way their word runs. */
private const val MinStep = 1e-6f
