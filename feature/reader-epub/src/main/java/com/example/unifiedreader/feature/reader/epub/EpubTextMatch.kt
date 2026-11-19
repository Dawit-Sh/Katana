package com.example.unifiedreader.feature.reader.epub

import kotlin.math.abs

/**
 * Finding a quote in a chapter's text the way a selection quotes it: the words and some text before
 * and after them. Spaces, line breaks, soft hyphens and zero-width marks don't count, as a
 * selection's text breaks lines between paragraphs where the document may have none.
 */
internal fun Char.isIgnorable(): Boolean = when (this) {
    '\u00A0', '\u00AD', '\uFEFF', in '\u200B'..'\u200D' -> true
    else -> isWhitespace()
}

/** [text] without its ignorable characters; [origin] is where each kept character was in the original. */
internal class SqueezedText(val text: String, val origin: IntArray)

internal fun squeeze(value: String): SqueezedText {
    val kept = StringBuilder(value.length)
    val origin = IntArray(value.length)
    value.forEachIndexed { index, char ->
        if (!char.isIgnorable()) {
            origin[kept.length] = index
            kept.append(char)
        }
    }
    return SqueezedText(kept.toString(), origin.copyOf(kept.length))
}

/**
 * Where in [haystack] (squeezed) [needle] is: the occurrence whose text before and after best
 * matches [before] and [after], then the one nearest [near] (an offset in the original text).
 */
internal fun bestOccurrence(haystack: SqueezedText, needle: String, before: String, after: String, near: Int?): Int? {
    var best: Int? = null
    var bestScore = -1
    var bestDistance = Int.MAX_VALUE
    var at = haystack.text.indexOf(needle)
    while (at >= 0) {
        val score = commonSuffix(haystack.text, at, before) + commonPrefix(haystack.text, at + needle.length, after)
        val distance = near?.let { abs(haystack.origin[at] - it) } ?: 0
        if (score > bestScore || (score == bestScore && distance < bestDistance)) {
            best = at
            bestScore = score
            bestDistance = distance
        }
        at = haystack.text.indexOf(needle, at + 1)
    }
    return best
}

private fun commonSuffix(text: String, end: Int, wanted: String): Int {
    var count = 0
    while (count < wanted.length && end - count - 1 >= 0 && text[end - count - 1] == wanted[wanted.length - count - 1]) count += 1
    return count
}

private fun commonPrefix(text: String, start: Int, wanted: String): Int {
    var count = 0
    while (count < wanted.length && start + count < text.length && text[start + count] == wanted[count]) count += 1
    return count
}
