package com.example.unifiedreader.feature.reader.pdf

import com.example.unifiedreader.feature.annotations.PageTextRect
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The text of one PDF page and where each character sits, read once (by [PdfTextSource]) and kept while
 * the page is near. A box is four fractions of the page (left, top, right, bottom, from the top left);
 * characters PDFium makes up (the space between words, a line's end) have none. Each character also
 * belongs to a line, whose height every selection and highlight on it takes, so a short word gets a box
 * as tall as its line's.
 *
 * [aspect] is the page's height over its width: distances on the page are measured in page widths.
 */
internal class PdfPageText(
    val pageIndex: Int,
    val text: String,
    private val boxes: FloatArray,
    val aspect: Float,
) {
    val length: Int get() = text.length

    private val lineOf = IntArray(text.length) { NoLine }
    private val lineTops = ArrayList<Float>()
    private val lineBottoms = ArrayList<Float>()

    init {
        require(boxes.size == text.length * BoxSize) { "One box per character" }
        groupLines()
    }

    fun hasBox(index: Int): Boolean = index in text.indices && !boxes[index * BoxSize + Left].isNaN()

    /**
     * The character nearest to ([x], [y]), its line's height counting as its own, within [maxDistance]
     * (in page widths); spaces and the characters PDFium made up never count. Null when none is that close.
     */
    fun charNear(x: Float, y: Float, maxDistance: Float = Float.MAX_VALUE): Int? {
        var best: Int? = null
        var bestDistance = maxDistance
        for (index in text.indices) {
            if (!hasBox(index) || text[index].isWhitespace()) continue
            val line = lineOf[index]
            val at = index * BoxSize
            val dx = max(max(boxes[at + Left] - x, x - boxes[at + Right]), 0f)
            val dy = max(max(lineTops[line] - y, y - lineBottoms[line]), 0f) * aspect
            val distance = hypot(dx, dy)
            if (distance == 0f) return index
            if (distance < bestDistance) {
                best = index
                bestDistance = distance
            }
        }
        return best
    }

    /** The word [index] is in, as the characters from its first up to past its last; a mark alone if it isn't in one. */
    fun wordAt(index: Int): IntRange {
        if (index !in text.indices) return IntRange.EMPTY
        if (!isWordChar(index)) return index..index
        var start = index
        while (start > 0 && joinsWord(start - 1, step = -1)) start--
        var end = index
        while (end < text.lastIndex && joinsWord(end + 1, step = 1)) end++
        return start..end
    }

    /** One box per line for the characters [start] up to [end] (not included), each as tall as its line. */
    fun rects(start: Int, end: Int): List<PageTextRect> {
        val rects = ArrayList<PageTextRect>()
        var line = NoLine
        var left = 0f
        var right = 0f
        for (index in start.coerceAtLeast(0) until end.coerceAtMost(text.length)) {
            if (!hasBox(index)) continue
            val at = index * BoxSize
            if (lineOf[index] != line) {
                if (line != NoLine) rects += PageTextRect(left, lineTops[line], right, lineBottoms[line])
                line = lineOf[index]
                left = boxes[at + Left]
                right = boxes[at + Right]
            } else {
                left = min(left, boxes[at + Left])
                right = max(right, boxes[at + Right])
            }
        }
        if (line != NoLine) rects += PageTextRect(left, lineTops[line], right, lineBottoms[line])
        return rects
    }

    /** The words [start] up to [end] (not included) as a reader would copy them: lines joined with spaces. */
    fun textOf(start: Int, end: Int): String = cleanPdfText(text.substring(start.coerceIn(0, length), end.coerceIn(0, length)))

    private fun isWordChar(index: Int): Boolean = text[index].isLetterOrDigit()

    /** Whether [index] belongs to the word next to it: a letter or digit, or an apostrophe or hyphen between two. */
    private fun joinsWord(index: Int, step: Int): Boolean = when {
        isWordChar(index) -> true
        text[index] in WordJoiners -> (index + step) in text.indices && isWordChar(index + step)
        else -> false
    }

    /**
     * A new line starts after a line break PDFium put in the text, or at a character beside no part of the line
     * so far. A raised footnote number still overlaps its line, so it stays on it.
     */
    private fun groupLines() {
        var current = NoLine
        var broke = false
        for (index in text.indices) {
            if (!hasBox(index)) {
                if (text[index] == '\n' || text[index] == '\r') broke = true
                continue
            }
            val at = index * BoxSize
            val top = boxes[at + Top]
            val bottom = boxes[at + Bottom]
            val beside = current != NoLine && top < lineBottoms[current] && bottom > lineTops[current]
            if (current == NoLine || broke || !beside) {
                lineTops += top
                lineBottoms += bottom
                current = lineTops.lastIndex
            } else {
                lineTops[current] = min(lineTops[current], top)
                lineBottoms[current] = max(lineBottoms[current], bottom)
            }
            broke = false
            lineOf[index] = current
        }
    }

    companion object {
        /** A box's four numbers, in this order. */
        const val BoxSize = 4
        const val Left = 0
        const val Top = 1
        const val Right = 2
        const val Bottom = 3
        private const val NoLine = -1
        private val WordJoiners = charArrayOf('\'', '’', '-', '‐', '_')
    }
}

/**
 * Text as PDFium gives it, made readable: a word broken over two lines with a hyphen is joined again, other
 * line breaks become spaces, and the control characters PDFium uses for its own marks are dropped.
 */
internal fun cleanPdfText(raw: String): String = raw
    .replace(BrokenWord, "")
    .replace(HyphenAtLineEnd, "-")
    .replace(LineBreak, " ")
    .replace(ControlChars, "")
    .replace(Spaces, " ")
    .trim()

/** PDFium's mark for a hyphen it found at a line's end (U+0002 or a soft hyphen), and the break after it. */
private val BrokenWord = Regex("[\\u0002\\u00AD\\uFFFE][ \\t]*\\r?\\n")
private val HyphenAtLineEnd = Regex("-[ \\t]*\\r?\\n")
private val LineBreak = Regex("\\s*\\r?\\n\\s*")
private val ControlChars = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u00AD\\uFFFE\\uFFFF]")
private val Spaces = Regex("[ \\t]{2,}")
