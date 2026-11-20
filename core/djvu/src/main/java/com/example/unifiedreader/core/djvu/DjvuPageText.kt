package com.example.unifiedreader.core.djvu

/**
 * A DjVu page's hidden text: its words in reading order, each with its line and its box. Boxes are in DjVu's own
 * terms: pixels of the page as stored ([width] by [height]), from its bottom left corner, y going up, before the
 * page's [rotation] (quarter turns anticlockwise) turns it the way it is shown.
 */
class DjvuPageText(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val words: List<DjvuTextWord>,
)

/** A word of a page's hidden text, on line [line] (from 0, in reading order). */
data class DjvuTextWord(
    val text: String,
    val line: Int,
    val left: Int,
    val bottom: Int,
    val right: Int,
    val top: Int,
)

/**
 * The page text from what the native call gives ({UTF-8 bytes, numbers}: the stored page's width, height and
 * rotation, then seven numbers a word: line, first byte, past-last byte, xmin, ymin, xmax, ymax). Words that
 * don't fit the bytes are left out; null when nothing usable is left.
 */
internal fun djvuPageTextOf(raw: Array<Any?>): DjvuPageText? {
    val bytes = raw.getOrNull(0) as? ByteArray
    val numbers = (raw.getOrNull(1) as? IntArray)?.takeIf { it.size > NumbersAhead && it[0] > 0 && it[1] > 0 }
    if (bytes == null || numbers == null) return null
    val words = (NumbersAhead until numbers.size - NumbersPerWord + 1 step NumbersPerWord).mapNotNull { at ->
        wordAt(bytes, numbers, at)
    }
    return words.takeIf { it.isNotEmpty() }?.let {
        DjvuPageText(width = numbers[0], height = numbers[1], rotation = numbers[2] and RotationMask, words = it)
    }
}

private fun wordAt(bytes: ByteArray, numbers: IntArray, at: Int): DjvuTextWord? {
    val start = numbers[at + 1]
    val end = numbers[at + 2]
    if (start < 0 || end > bytes.size || end <= start) return null
    val text = String(bytes, start, end - start, Charsets.UTF_8)
    return DjvuTextWord(
        text = text,
        line = numbers[at],
        left = numbers[at + 3],
        bottom = numbers[at + 4],
        right = numbers[at + 5],
        top = numbers[at + 6],
    ).takeIf { word -> word.right > word.left && word.top > word.bottom && word.text.isNotBlank() }
}

private const val NumbersAhead = 3
private const val NumbersPerWord = 7
private const val RotationMask = 3
