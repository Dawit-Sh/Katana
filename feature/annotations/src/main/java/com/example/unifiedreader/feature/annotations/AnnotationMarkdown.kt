package com.example.unifiedreader.feature.annotations

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Bookmarks, highlights, their notes and saved words as Markdown, for one book or for every book:
 * the bookmarked pages with their names and notes, then quotes in reading order under their chapter,
 * each note under its quote, then the words with the sentence they came from and their notes.
 */
object AnnotationMarkdown {
    fun book(
        book: AnnotatedBook,
        highlights: List<ReaderHighlight>,
        words: List<SavedWord>,
        exportedOn: LocalDate,
        bookmarks: List<ReaderBookmark> = emptyList(),
    ): String = buildString {
        appendLine("# ${book.title.oneLine()}")
        book.author?.takeIf(String::isNotBlank)?.let { author -> appendLine("*${author.oneLine()}*") }
        appendLine()
        appendLine(summary(exportedOn, highlights.size, words.size, bookmarks.size))
        appendBookBody(highlights, words, bookmarks, headingLevel = 2)
    }.trimEnd() + "\n"

    /** Every book with a highlight, a bookmark or a saved word, in title order. */
    fun library(
        highlights: List<ReaderHighlight>,
        words: List<SavedWord>,
        exportedOn: LocalDate,
        bookmarks: List<ReaderBookmark> = emptyList(),
    ): String = buildString {
        appendLine("# Katana notebook")
        appendLine()
        appendLine(summary(exportedOn, highlights.size, words.size, bookmarks.size))
        val highlightsByBook = highlights.groupBy { it.book.itemId }
        val wordsByBook = words.groupBy { it.book.itemId }
        val bookmarksByBook = bookmarks.groupBy { it.book.itemId }
        val books = (highlights.map(ReaderHighlight::book) + bookmarks.map(ReaderBookmark::book) + words.map(SavedWord::book))
            .distinctBy(AnnotatedBook::itemId)
            .sortedBy { it.title.lowercase(Locale.ROOT) }
        books.forEach { book ->
            appendLine()
            appendLine("## ${book.title.oneLine()}")
            book.author?.takeIf(String::isNotBlank)?.let { author -> appendLine("*${author.oneLine()}*") }
            appendBookBody(
                highlights = highlightsByBook[book.itemId].orEmpty().inReadingOrder(),
                words = wordsByBook[book.itemId].orEmpty().inReadingOrderWords(),
                bookmarks = bookmarksByBook[book.itemId].orEmpty(),
                headingLevel = 3,
            )
        }
    }.trimEnd() + "\n"

    /** A file name for the export: the book's title, or the notebook's, and the day. */
    fun fileName(book: AnnotatedBook?, exportedOn: LocalDate): String {
        val name = book?.title?.let(::safeFileName)?.takeIf(String::isNotEmpty) ?: "Katana notebook"
        return "$name ${exportedOn.format(DateTimeFormatter.ISO_LOCAL_DATE)}.md"
    }

    private fun StringBuilder.appendBookBody(
        highlights: List<ReaderHighlight>,
        words: List<SavedWord>,
        bookmarks: List<ReaderBookmark>,
        headingLevel: Int,
    ) {
        val section = "#".repeat(headingLevel)
        val chapter = "#".repeat(headingLevel + 1)
        appendBookmarks(bookmarks, section)
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
                appendListNote(word.note)
            }
        }
    }

    private fun StringBuilder.appendBookmarks(bookmarks: List<ReaderBookmark>, section: String) {
        if (bookmarks.isEmpty()) return
        appendLine()
        appendLine("$section Bookmarks")
        appendLine()
        bookmarks.inReadingOrderBookmarks().forEach { bookmark ->
            val text = bookmark.text?.oneLine()?.takeIf(String::isNotBlank)?.let { ": \"$it\"" }.orEmpty()
            val chapterTitle = bookmark.place.chapterTitle?.oneLine()?.takeIf(String::isNotBlank)?.let { " ($it)" }.orEmpty()
            val name = bookmark.title?.oneLine()?.takeIf(String::isNotBlank)?.let { "**$it**, " }.orEmpty()
            appendLine("- $name${bookmarkPlace(bookmark)}$text$chapterTitle")
            appendListNote(bookmark.note)
        }
    }

    private fun StringBuilder.appendQuote(text: String) {
        text.trim().lines().forEach { line -> appendLine(if (line.isBlank()) ">" else "> ${line.trim()}") }
    }

    private fun summary(exportedOn: LocalDate, highlights: Int, words: Int, bookmarks: Int): String =
        "Exported from Katana on ${exportedOn.format(DayFormat)}: " +
            listOfNotNull(
                count(highlights, "highlight"),
                bookmarks.takeIf { it > 0 }?.let { count(it, "bookmark") },
                count(words, "saved word"),
            ).joinToString(", ") + "."

    /** "Page 8 of 78", or how far through the book when the page is unknown. */
    fun bookmarkPlace(bookmark: ReaderBookmark): String {
        val page = bookmark.page?.takeIf { it > 0 }
        val pages = bookmark.pageCount?.takeIf { it > 0 }
        return when {
            page != null && pages != null -> "Page $page of $pages"
            page != null -> "Page $page"
            else -> bookmark.place.progression?.let { "${(it * PERCENT).roundToInt().coerceIn(0, PERCENT)}%" } ?: "Bookmark"
        }
    }

    private fun count(n: Int, noun: String): String = if (n == 1) "1 $noun" else "$n ${noun}s"

    private fun safeFileName(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|]"), " ").oneLine().take(MAX_FILE_NAME)

    private val DayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)

    private const val MAX_FILE_NAME = 80
    private const val PERCENT = 100
}

/** A bookmark's or a word's own note, under its line in the list. */
private fun StringBuilder.appendListNote(note: String?) {
    note?.trim()?.takeIf(String::isNotEmpty)?.let { appendLine("  Note: ${it.replace("\n", "\n  ")}") }
}
