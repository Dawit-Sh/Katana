package com.example.unifiedreader.feature.annotations

import java.util.Locale

/**
 * Searching marks, the same everywhere (a book's marks panel in the reader, the Bookmarks page, a
 * book's page there): a query matches a mark when its words hold it, case aside. A bookmark is
 * searched by the words it keeps from its page, its chapter, and its place ("page 12", "12 of 300",
 * "64%"), so a bookmark of a scanned page with no words still turns up by its page.
 */
object MarkSearch {
    /** The query as marks are matched against it; empty for a blank one, which matches every mark. */
    fun needle(query: String): String = query.trim().lowercase(Locale.ROOT)

    fun matches(bookmark: ReaderBookmark, query: String): Boolean = needle(query).let { it.isEmpty() || bookmark.holds(it) }

    fun matches(highlight: ReaderHighlight, query: String): Boolean = needle(query).let { it.isEmpty() || highlight.holds(it) }

    fun matches(word: SavedWord, query: String): Boolean = needle(query).let { it.isEmpty() || word.holds(it) }
}

/** [needle] is [MarkSearch.needle]'s. */
internal fun ReaderBookmark.holds(needle: String): Boolean =
    text.holds(needle) || place.chapterTitle.holds(needle) || AnnotationMarkdown.bookmarkPlace(this).holds(needle)

internal fun ReaderHighlight.holds(needle: String): Boolean =
    text.holds(needle) || note.holds(needle) || place.chapterTitle.holds(needle)

internal fun SavedWord.holds(needle: String): Boolean = word.holds(needle) || sentence.holds(needle) || note.holds(needle)

internal fun String?.holds(needle: String): Boolean = this?.lowercase(Locale.ROOT)?.contains(needle) == true
