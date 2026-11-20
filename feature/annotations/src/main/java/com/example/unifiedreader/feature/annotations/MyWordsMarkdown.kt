package com.example.unifiedreader.feature.annotations

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The My words page's export: the saved words under their books (by title), each book's words in
 * reading order with the sentence they came from and their notes, as a book's export lists them.
 */
object MyWordsMarkdown {
    fun words(words: List<SavedWord>, exportedOn: LocalDate): String = buildString {
        appendLine("# $TITLE")
        appendLine()
        appendLine("Exported from Katana on ${exportedOn.format(DayFormat)}: ${MarkCounts.count(words.size, "saved word")}.")
        words.groupBy { it.book.itemId }.values
            .sortedBy { inBook -> inBook.first().book.title.lowercase(Locale.ROOT) }
            .forEach { inBook ->
                val book = inBook.first().book
                appendLine()
                appendLine("## ${book.title.oneLine()}")
                book.author?.takeIf(String::isNotBlank)?.let { author -> appendLine("*${author.oneLine()}*") }
                appendLine()
                appendWordList(inBook)
            }
    }.trimEnd() + "\n"

    /** "My words 2026-11-20.md". */
    fun fileName(exportedOn: LocalDate): String = "$TITLE ${exportedOn.format(DateTimeFormatter.ISO_LOCAL_DATE)}.md"

    private const val TITLE = "My words"
    private val DayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)
}
