package com.example.unifiedreader.feature.annotations

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Highlights, their notes and saved words as Markdown, for one book or for every book: quotes in
 * reading order under their chapter, each note under its quote, then the words with the sentence
 * they came from.
 */
object AnnotationMarkdown {
    fun book(
        book: AnnotatedBook,
        highlights: List<ReaderHighlight>,
        words: List<SavedWord>,
        exportedOn: LocalDate,
    ): String = buildString {
        appendLine("# ${book.title.oneLine()}")
        book.author?.takeIf(String::isNotBlank)?.let { author -> appendLine("*${author.oneLine()}*") }
        appendLine()
        appendLine(summary(exportedOn, highlights.size, words.size))
        appendBookBody(highlights, words, headingLevel = 2)
    }.trimEnd() + "\n"

    /** Every book with a highlight or a saved word, in title order. */
    fun library(
        highlights: List<ReaderHighlight>,
        words: List<SavedWord>,
        exportedOn: LocalDate,
    ): String = buildString {
        appendLine("# yabaiSY notebook")
        appendLine()
        appendLine(summary(exportedOn, highlights.size, words.size))
        val highlightsByBook = highlights.groupBy { it.book.itemId }
        val wordsByBook = words.groupBy { it.book.itemId }
        val books = (highlights.map(ReaderHighlight::book) + words.map(SavedWord::book))
            .distinctBy(AnnotatedBook::itemId)
            .sortedBy { it.title.lowercase(Locale.ROOT) }
        books.forEach { book ->
            appendLine()
            appendLine("## ${book.title.oneLine()}")
            book.author?.takeIf(String::isNotBlank)?.let { author -> appendLine("*${author.oneLine()}*") }
            appendBookBody(
                highlights = highlightsByBook[book.itemId].orEmpty().inReadingOrder(),
                words = wordsByBook[book.itemId].orEmpty().inReadingOrderWords(),
                headingLevel = 3,
            )
        }
    }.trimEnd() + "\n"

    /** A file name for the export: the book's title, or the notebook's, and the day. */
    fun fileName(book: AnnotatedBook?, exportedOn: LocalDate): String {
        val name = book?.title?.let(::safeFileName)?.takeIf(String::isNotEmpty) ?: "yabaiSY notebook"
        return "$name ${exportedOn.format(DateTimeFormatter.ISO_LOCAL_DATE)}.md"
    }

    private fun StringBuilder.appendBookBody(
        highlights: List<ReaderHighlight>,
        words: List<SavedWord>,
        headingLevel: Int,
    ) {
        val section = "#".repeat(headingLevel)
        val chapter = "#".repeat(headingLevel + 1)
        if (highlights.isNotEmpty()) {
            appendLine()
            appendLine("$section Highlights")
            var currentChapter: String? = null
            highlights.inReadingOrder().forEach { highlight ->
                val title = highlight.place.chapterTitle?.oneLine()?.takeIf(String::isNotBlank)
                if (title != null && title != currentChapter) {
                    appendLine()
                    appendLine("$chapter $title")
                    currentChapter = title
                }
                appendLine()
                appendQuote(highlight.text)
                highlight.note?.takeIf(String::isNotBlank)?.let { note ->
                    appendLine()
                    appendLine("Note: ${note.trim().replace("\n", "\n  ")}")
                }
            }
        }
        if (words.isNotEmpty()) {
            appendLine()
            appendLine("$section Words")
            appendLine()
            words.inReadingOrderWords().forEach { word ->
                val sentence = word.sentence?.oneLine()?.takeIf(String::isNotBlank)?.let { ": \"$it\"" }.orEmpty()
                val chapterTitle = word.place?.chapterTitle?.oneLine()?.takeIf(String::isNotBlank)?.let { " ($it)" }.orEmpty()
                appendLine("- **${word.word.oneLine()}**$sentence$chapterTitle")
            }
        }
    }

    private fun StringBuilder.appendQuote(text: String) {
        text.trim().lines().forEach { line -> appendLine(if (line.isBlank()) ">" else "> ${line.trim()}") }
    }

    private fun summary(exportedOn: LocalDate, highlights: Int, words: Int): String =
        "Exported from yabaiSY on ${exportedOn.format(DayFormat)}: " +
            "${count(highlights, "highlight")}, ${count(words, "saved word")}."

    private fun count(n: Int, noun: String): String = if (n == 1) "1 $noun" else "$n ${noun}s"

    private fun safeFileName(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|]"), " ").oneLine().take(MAX_FILE_NAME)

    private val DayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)

    private const val MAX_FILE_NAME = 80
}
