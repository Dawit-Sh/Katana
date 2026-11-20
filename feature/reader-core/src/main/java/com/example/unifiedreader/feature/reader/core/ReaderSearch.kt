package com.example.unifiedreader.feature.reader.core

/*
 * Search in a book, the parts every reader shares (EPUB through Readium, PDF through its PDFium text): the matches'
 * snippets, how they group, the line under the field, "3 of 12", and stepping from match to match. The panel and the
 * dock strip that show them are ReaderSearchPanel.kt.
 */

/** The match and a few words either side, cut at whole words: "…to the [bento] shop. He…". */
data class ReaderSearchSnippet(
    val before: String,
    val match: String,
    val after: String,
)

enum class ReaderSearchStatus {
    /** Nothing asked yet, or too little typed. */
    Idle,
    Searching,
    Done,

    /** Stopped at the reader's limit of matches. */
    Capped,
    Failed,
}

/** The matches of one chapter (or page), in book order: [indices] into the results. */
data class ReaderSearchGroup(
    val title: String,
    val indices: List<Int>,
)

/** What a book is divided into, for the line under the field: "3 chapters", "12 pages". */
class ReaderSearchParts(val one: String, val many: String) {
    fun count(n: Int): String = if (n == 1) "1 $one" else "$n $many"

    companion object {
        val Chapters = ReaderSearchParts("chapter", "chapters")
        val Pages = ReaderSearchParts("page", "pages")
    }
}

/** Two letters at least, or one character of a script without spaces between words (Chinese, Japanese). */
fun isReaderSearchQueryLongEnough(query: String): Boolean {
    val text = query.trim()
    return text.length >= 2 || text.any { it.code >= CjkScriptsStart }
}

/** The match with [before] and [after] cut to a few whole words, "…" where they were cut. */
fun readerSearchSnippet(
    before: String?,
    match: String?,
    after: String?,
    maxBefore: Int = SnippetMaxBefore,
    maxAfter: Int = SnippetMaxAfter,
): ReaderSearchSnippet {
    val lead = before.orEmpty().collapseSpaces().trimStart()
    val tail = after.orEmpty().collapseSpaces().trimEnd()
    val shortLead = if (lead.length <= maxBefore) {
        lead
    } else {
        val cut = lead.takeLast(maxBefore)
        val wordStart = cut.indexOf(' ').takeIf { it in 0 until cut.length - 1 }
        "…" + (wordStart?.let { cut.substring(it + 1) } ?: cut)
    }
    val shortTail = if (tail.length <= maxAfter) {
        tail
    } else {
        val cut = tail.take(maxAfter)
        val wordEnd = cut.lastIndexOf(' ').takeIf { it > 0 }
        (wordEnd?.let { cut.substring(0, it) } ?: cut) + "…"
    }
    return ReaderSearchSnippet(before = shortLead, match = match.orEmpty().collapseSpaces(), after = shortTail)
}

/** Matches next to each other in the same chapter go under one heading; [chapterOf] names a match's chapter. */
fun <T> readerSearchGroups(
    results: List<T>,
    chapterOf: (T) -> String,
): List<ReaderSearchGroup> {
    val groups = mutableListOf<ReaderSearchGroup>()
    results.forEachIndexed { index, result ->
        val title = chapterOf(result)
        val last = groups.lastOrNull()
        if (last != null && last.title == title) {
            groups[groups.lastIndex] = last.copy(indices = last.indices + index)
        } else {
            groups += ReaderSearchGroup(title = title, indices = listOf(index))
        }
    }
    return groups
}

/** The line under the field: "12 results in 3 chapters", "Searching…", or null before a search. */
fun readerSearchSummary(
    status: ReaderSearchStatus,
    resultCount: Int,
    groupCount: Int,
    parts: ReaderSearchParts = ReaderSearchParts.Chapters,
): String? {
    val results = if (resultCount == 1) "1 result" else "$resultCount results"
    val groups = parts.count(groupCount)
    return when (status) {
        ReaderSearchStatus.Idle -> null
        ReaderSearchStatus.Searching -> if (resultCount == 0) "Searching…" else "$results so far"
        ReaderSearchStatus.Done -> if (resultCount == 0) "No results" else "$results in $groups"
        ReaderSearchStatus.Capped -> "The first $resultCount results, in $groups"
        ReaderSearchStatus.Failed -> "This book can't be searched"
    }
}

/** "3 of 12", or "12 results" before one is chosen. */
fun readerSearchPosition(currentIndex: Int?, resultCount: Int): String = when {
    currentIndex != null -> "${currentIndex + 1} of $resultCount"
    resultCount == 1 -> "1 result"
    else -> "$resultCount results"
}

/**
 * The match to step to: the one after (or before) [currentIndex], round past the ends; before any is
 * chosen, the first match after the reader's place ([fromProgress]) or the last one before it.
 */
fun nextReaderSearchIndex(
    progressions: List<Double?>,
    currentIndex: Int?,
    forward: Boolean,
    fromProgress: Double,
): Int? {
    val count = progressions.size
    return when {
        count == 0 -> null
        currentIndex != null -> Math.floorMod(currentIndex + if (forward) 1 else -1, count)
        forward -> progressions.indexOfFirst { (it ?: 0.0) >= fromProgress }.takeIf { it >= 0 } ?: 0
        else -> progressions.indexOfLast { (it ?: 0.0) < fromProgress }.takeIf { it >= 0 } ?: (count - 1)
    }
}

private fun String.collapseSpaces(): String = replace(Whitespace, " ")

private const val SnippetMaxBefore = 40
private const val SnippetMaxAfter = 60
private const val CjkScriptsStart = 0x2E80
private val Whitespace = Regex("""\s+""")
