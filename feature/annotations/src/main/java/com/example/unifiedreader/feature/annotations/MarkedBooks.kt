package com.example.unifiedreader.feature.annotations

import java.util.Locale

/** Which marks the Bookmarks page shows. */
enum class MarkKind { All, Highlights, Bookmarks, Words }

/** How the Bookmarks page orders its books. */
enum class MarkedBookOrder { LastMarked, Title }

/** One book's marks: its highlights, bookmarks and saved words, each in reading order. */
data class MarkedBook(
    val book: AnnotatedBook,
    val highlights: List<ReaderHighlight>,
    val bookmarks: List<ReaderBookmark>,
    val words: List<SavedWord>,
) {
    /** When the latest mark was made or changed. */
    val lastMarkedAtEpochMillis: Long =
        listOfNotNull(
            highlights.maxOfOrNull(ReaderHighlight::updatedAtEpochMillis),
            bookmarks.maxOfOrNull(ReaderBookmark::createdAtEpochMillis),
            words.maxOfOrNull(SavedWord::createdAtEpochMillis),
        ).maxOrNull() ?: 0L

    fun has(kind: MarkKind): Boolean = when (kind) {
        MarkKind.All -> highlights.isNotEmpty() || bookmarks.isNotEmpty() || words.isNotEmpty()
        MarkKind.Highlights -> highlights.isNotEmpty()
        MarkKind.Bookmarks -> bookmarks.isNotEmpty()
        MarkKind.Words -> words.isNotEmpty()
    }

    /** Whether the book's name, its author or one of its marks of [kind] holds [query]. */
    fun matches(query: String, kind: MarkKind = MarkKind.All): Boolean {
        val needle = MarkSearch.needle(query)
        return needle.isEmpty() || book.title.holds(needle) || book.author.holds(needle) || marksHold(needle, kind)
    }

    /** This book with only its marks that hold [query]; all of them for a blank one. */
    fun only(query: String): MarkedBook {
        val needle = MarkSearch.needle(query)
        if (needle.isEmpty()) return this
        return copy(
            highlights = highlights.filter { it.holds(needle) },
            bookmarks = bookmarks.filter { it.holds(needle) },
            words = words.filter { it.holds(needle) },
        )
    }

    private fun marksHold(needle: String, kind: MarkKind): Boolean =
        (kind in HighlightKinds && highlights.any { it.holds(needle) }) ||
            (kind in BookmarkKinds && bookmarks.any { it.holds(needle) }) ||
            (kind in WordKinds && words.any { it.holds(needle) })

    private companion object {
        val HighlightKinds = setOf(MarkKind.All, MarkKind.Highlights)
        val BookmarkKinds = setOf(MarkKind.All, MarkKind.Bookmarks)
        val WordKinds = setOf(MarkKind.All, MarkKind.Words)
    }
}

/** Every book with a highlight, a bookmark or a saved word, and the totals across them. */
data class MarkedLibrary(val books: List<MarkedBook>) {
    val highlightCount: Int = books.sumOf { it.highlights.size }
    val bookmarkCount: Int = books.sumOf { it.bookmarks.size }
    val wordCount: Int = books.sumOf { it.words.size }

    val isEmpty: Boolean get() = books.isEmpty()

    fun book(itemId: String): MarkedBook? = books.firstOrNull { it.book.itemId == itemId }

    /** The books a filter and a search show, in [order]. */
    fun shown(kind: MarkKind, query: String, order: MarkedBookOrder): List<MarkedBook> {
        val shown = books.filter { it.has(kind) && it.matches(query, kind) }
        return when (order) {
            MarkedBookOrder.LastMarked -> shown.sortedByDescending(MarkedBook::lastMarkedAtEpochMillis)
            MarkedBookOrder.Title -> shown.sortedBy { it.book.title.lowercase(Locale.ROOT) }
        }
    }

    /** Every saved word, the newest first. */
    val words: List<SavedWord> get() = books.flatMap(MarkedBook::words).sortedByDescending(SavedWord::createdAtEpochMillis)

    companion object {
        val Empty = MarkedLibrary(emptyList())

        fun of(
            highlights: List<ReaderHighlight>,
            bookmarks: List<ReaderBookmark>,
            words: List<SavedWord>,
        ): MarkedLibrary {
            val highlightsByBook = highlights.groupBy { it.book.itemId }
            val bookmarksByBook = bookmarks.groupBy { it.book.itemId }
            val wordsByBook = words.groupBy { it.book.itemId }
            // The book as its latest mark names it: titles are copied when a mark is made.
            val latestBook = buildMap<String, Pair<Long, AnnotatedBook>> {
                fun offer(book: AnnotatedBook, at: Long) {
                    val known = get(book.itemId)
                    if (known == null || at > known.first) put(book.itemId, at to book)
                }
                highlights.forEach { offer(it.book, it.updatedAtEpochMillis) }
                bookmarks.forEach { offer(it.book, it.createdAtEpochMillis) }
                words.forEach { offer(it.book, it.createdAtEpochMillis) }
            }
            return MarkedLibrary(
                latestBook.map { (itemId, latest) ->
                    MarkedBook(
                        book = latest.second,
                        highlights = highlightsByBook[itemId].orEmpty().inReadingOrder(),
                        bookmarks = bookmarksByBook[itemId].orEmpty().inReadingOrderBookmarks(),
                        words = wordsByBook[itemId].orEmpty().inReadingOrderWords(),
                    )
                }.sortedByDescending(MarkedBook::lastMarkedAtEpochMillis),
            )
        }
    }
}
