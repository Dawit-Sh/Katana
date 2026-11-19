package com.example.unifiedreader.feature.reader.epub

/**
 * One step of an EPUB CFI path (https://idpf.org/epub/linking/cfi/): `/index`, the id the element
 * there should have (`[id]`), and on the last step of a text position its character `:offset`.
 * Even indexes are elements (2 the first), odd ones the text between them (1 before the first).
 */
internal data class CfiStep(val index: Int, val id: String? = null, val offset: Int? = null)

/**
 * A range CFI as highlights use them, `epubcfi(/6/8!/4/2[ch1]/10,/1:5,/3:2)`: the spine item it is
 * in ([spineIndex], counting every item of the package's spine from 0) and the paths from the
 * chapter's root element to where the words start and end.
 */
internal data class EpubCfiRange(
    val spineIndex: Int,
    val start: List<CfiStep>,
    val end: List<CfiStep>,
)

/**
 * Reads and writes EPUB CFIs the way Grimmory's web reader does (foliate-js's `epubcfi.js`): a
 * range's shared steps go first, then the start's and the end's own, with the elements' ids
 * asserted, so a highlight made here reads the same there. The KOReader plugin's CFIs (no ids)
 * read too. Temporal, spatial and text assertions are read and ignored.
 */
internal object EpubCfi {
    fun parse(cfi: String): EpubCfiRange? {
        val trimmed = cfi.trim()
        val inner = trimmed.takeIf { it.startsWith(PREFIX) && it.endsWith(")") }?.substring(PREFIX.length, trimmed.length - 1)
        return inner?.let(::tokenizeCfi)?.let(::rangeOf)
    }

    /** The range CFI from [packageSteps] (the spine and the item in it) to the [start] and [end] paths in the chapter. */
    fun formatRange(packageSteps: List<CfiStep>, start: List<CfiStep>, end: List<CfiStep>): String {
        // As foliate-js: a step with an offset (even 0) or a different index ends the shared part.
        val shared = start.zip(end).takeWhile { (a, b) -> a.index == b.index && !a.offset.isSet() && !b.offset.isSet() }.size
        return buildString {
            append(PREFIX)
            packageSteps.forEach { append(it.format()) }
            append('!')
            start.take(shared).forEach { append(it.format()) }
            append(',')
            start.drop(shared).forEach { append(it.format()) }
            append(',')
            end.drop(shared).forEach { append(it.format()) }
            append(')')
        }
    }

    private const val PREFIX = "epubcfi("
}

private fun rangeOf(tokens: List<CfiToken>): EpubCfiRange? {
    val sections = tokens.splitAt { it is CfiToken.Comma }.map(::parseCfiPath)
    val (start, end) = when (sections.size) {
        1 -> sections[0] to sections[0]
        RANGE_SECTIONS -> joinCfiPaths(sections[0], sections[1]) to joinCfiPaths(sections[0], sections[2])
        else -> emptyList<List<CfiStep>>() to emptyList()
    }
    val startPart = chapterPart(start)
    val endPart = chapterPart(end)
    return if (startPart != null && endPart != null && startPart.first == endPart.first) {
        EpubCfiRange(spineIndex = startPart.first, start = startPart.second, end = endPart.second)
    } else {
        null
    }
}

// The spine index and the chapter's steps of a full path (the package's part, `!`, the chapter's).
private fun chapterPart(path: List<List<CfiStep>>): Pair<Int, List<CfiStep>>? {
    val itemStep = path.firstOrNull()?.lastOrNull()?.takeIf { it.index >= 2 && it.index % 2 == 0 }
    val content = path.getOrNull(1)?.takeIf { it.isNotEmpty() && path.size == 2 }
    return if (itemStep != null && content != null) (itemStep.index / 2 - 1) to content else null
}

private fun Int?.isSet(): Boolean = this != null && this != 0

private fun CfiStep.format(): String = buildString {
    append('/').append(index)
    id?.takeIf(String::isNotEmpty)?.let { append('[').append(escapeCfi(it)).append(']') }
    if (offset != null && index % 2 == 1) append(':').append(offset)
}

private fun escapeCfi(value: String): String = buildString {
    value.forEach { char ->
        if (char in CFI_ESCAPED) append('^')
        append(char)
    }
}

private const val RANGE_SECTIONS = 3
private const val CFI_ESCAPED = "^[](),;="
