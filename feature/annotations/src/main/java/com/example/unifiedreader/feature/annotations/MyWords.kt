package com.example.unifiedreader.feature.annotations

import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** A book on My words' filter: the book and how many of its words are saved. */
data class MyWordsBook(val book: AnnotatedBook, val count: Int)

/**
 * Every saved word for the My words page: the newest first, filtered by a book and a search, the
 * books to filter by and how many words are due for review.
 */
class MyWordsList(words: List<SavedWord>) {
    /** Every word, the newest first. */
    val words: List<SavedWord> = words.sortedByDescending(SavedWord::createdAtEpochMillis)

    val isEmpty: Boolean get() = words.isEmpty()

    /** The books the words come from, by title, each named as its latest word names it. */
    val books: List<MyWordsBook> =
        this.words.groupBy { it.book.itemId }
            .map { (_, inBook) -> MyWordsBook(book = inBook.first().book, count = inBook.size) }
            .sortedBy { it.book.title.lowercase(Locale.ROOT) }

    /** The words of [bookId], or every word for null; newest first. */
    fun inBook(bookId: String?): List<SavedWord> = if (bookId == null) words else words.filter { it.book.itemId == bookId }

    /**
     * The words a book filter and a search show, the newest first. A search finds a word by itself,
     * the sentence it came from, the book's title and the word's own note.
     */
    fun shown(query: String, bookId: String?): List<SavedWord> {
        val needle = MarkSearch.needle(query)
        return inBook(bookId).filter { word -> needle.isEmpty() || word.holds(needle) || word.book.title.holds(needle) }
    }

    /** How many of [bookId]'s words (every word for null) are up for review at [atEpochMillis]. */
    fun dueCount(atEpochMillis: Long, bookId: String? = null): Int =
        inBook(bookId).count { word -> WordReviewSchedule.isDue(word, atEpochMillis) }

    /** The book [bookId] names, while it still has words. */
    fun book(bookId: String?): MyWordsBook? = bookId?.let { id -> books.firstOrNull { it.book.itemId == id } }
}

/**
 * Where in its book a word was met: "p. 11" from the reader's page there (an EPUB's position), or
 * how far through the book; null when the word was saved without a place.
 */
fun wordPlace(word: SavedWord): String? {
    val place = word.place ?: return null
    val page = locatorPosition(place.locatorJson)
    return when {
        page != null -> "p. $page"
        place.progression != null -> "${(place.progression * PERCENT).roundToInt().coerceIn(0, PERCENT)}%"
        else -> null
    }
}

/**
 * Where [word] stands in [sentence], to set it in bold: the first place it stands as a whole word,
 * case aside, else the first place it appears at all; null when it isn't there.
 */
fun wordSpan(sentence: String, word: String): IntRange? {
    val wanted = word.trim()
    if (wanted.isEmpty()) return null
    val whole = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(wanted) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    val at = sentence.indexOf(wanted, ignoreCase = true)
    return whole.find(sentence)?.range ?: at.takeIf { it >= 0 }?.let { it until it + wanted.length }
}

/**
 * The meaning line under [word] on My words: what [WordMeanings] gave for it. A meaning stored with
 * the word itself (a dictionary's Add to My words) goes first here once saved words keep one.
 */
fun meaningLine(word: SavedWord, meanings: Map<String, String>): String? = meanings[word.id]

/** The page a Readium locator names (`locations.position`, which the EPUB reader shows as its page). */
internal fun locatorPosition(locatorJson: String): Int? = try {
    val locations = Json.parseToJsonElement(locatorJson) as? JsonObject
    ((locations?.get("locations") as? JsonObject)?.get("position"))?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
} catch (_: IllegalArgumentException) {
    null
}

private const val PERCENT = 100
