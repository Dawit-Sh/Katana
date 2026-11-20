package com.example.unifiedreader.feature.annotations

import com.example.unifiedreader.core.model.DeviceClock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the Bookmarks page, a book's marks and the word review say on screen. */
object BookmarksCopy {
    const val ExportAll = "Export all"
    const val Export = "Export"
    const val Exported = "Exported"
    const val ExportFailed = "Couldn't save the file"
    const val SearchPlaceholder = "Search bookmarks, highlights, notes and words"
    const val BookSearchPlaceholder = "Search this book's marks"
    const val ReviewWords = "Review words"
    const val Back = "Back to Bookmarks"
    const val BackToBook = "Back to the book"
    const val Continue = "Continue"
    const val Bookmarks = "Bookmarks"
    const val Highlights = "Highlights"
    const val Words = "Words"
    const val SavedWords = "Saved words"
    const val All = "All"
    const val LastMarked = "Last marked"
    const val Title = "Title"
    const val LookUp = "Look it up"
    const val Delete = "Delete"
    const val EmptyTitle = "No bookmarks yet"
    const val BookPageEmpty = "No bookmarks yet. While reading, tap the bookmark at the top to keep a page."

    // Where a local book's marks live, on its page.
    const val MarksSyncWithGrimmory = "Highlights and bookmarks sync with Grimmory."
    const val BookmarksSyncWithGrimmory = "Bookmarks sync with Grimmory."
    const val MarksNotOnGrimmory = "Not on Grimmory: highlights and bookmarks stay on this device."
    const val MarksOnThisDevice = "Highlights and bookmarks stay on this device."
    const val NothingInBook = "None of this book's marks match."
    const val EmptyBody = "Bookmark a page, highlight words or add a word to your dictionary while you read. They all gather here."
    const val NothingFoundTitle = "Nothing found"
    const val NothingFoundBody = "No book has marks that match. Try other words or another filter."
    const val BookmarkRemoved = "Bookmark removed"
    const val HighlightRemoved = "Highlight removed"
    const val Undo = "Undo"
    const val WordRemoved = "Word removed"

    const val ReviewTitle = "Review saved words"
    const val SavedWordLabel = "Saved word"
    const val YourNote = "Your note"
    const val NotePlaceholder = "Tap to add what it means, in your own words."
    const val NoteDialogTitle = "What it means"
    const val NoteFieldPlaceholder = "In your own words"
    const val Save = "Save"
    const val Cancel = "Cancel"
    const val Again = "Again"
    const val KnowIt = "I know it"
    const val ReviewHint = "Words you know come back less often. Again brings it back later in this round."
    const val NothingDueTitle = "Nothing to review today"
    const val NothingDueBody = "Every word is resting. Words you know come back after a day, then 3, 7, 14 and 30 days."
    const val ReviewAll = "Review all words"
    const val RoundDoneTitle = "Round done"
    const val NoWordsTitle = "No saved words yet"
    const val NoWordsBody = "Select a word in a book, then Add to dictionary. It comes back here as a card."

    /** "6 books · 24 highlights · 5 bookmarks · 12 saved words". */
    fun librarySummary(library: MarkedLibrary): String = listOf(
        MarkCounts.books(library.books.size),
        MarkCounts.highlights(library.highlightCount),
        MarkCounts.bookmarks(library.bookmarkCount),
        MarkCounts.count(library.wordCount, "saved word"),
    ).joinToString(" · ")

    /** "2 highlights · 1 bookmark · 1 word", leaving out what the book has none of. */
    fun bookSummary(book: MarkedBook): String = listOfNotNull(
        book.highlights.size.takeIf { it > 0 }?.let(MarkCounts::highlights),
        book.bookmarks.size.takeIf { it > 0 }?.let(MarkCounts::bookmarks),
        book.words.size.takeIf { it > 0 }?.let(MarkCounts::words),
    ).joinToString(" · ")

    fun continueAt(progress: String?): String = if (progress == null) Continue else "$Continue · $progress"

    fun roundProgress(position: Int, total: Int): String = "$position of $total · from every book"

    fun roundDone(known: Int): String = if (known == 1) "You went through 1 word." else "You went through $known words."

    fun lastMarked(atEpochMillis: Long, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String =
        "Last marked " + moment(atEpochMillis, today, zone)

    /** "today, 18:38", "yesterday", "16 Nov", or "16 Nov 2025" in another year. */
    fun moment(atEpochMillis: Long, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val at = Instant.ofEpochMilli(atEpochMillis).atZone(zone)
        val day = at.toLocalDate()
        return when {
            day == today -> "today, " + DeviceClock.format(at)
            day == today.minusDays(1) -> "yesterday"
            day.year == today.year -> at.format(DayFormat)
            else -> at.format(DayYearFormat)
        }
    }

    fun saved(atEpochMillis: Long, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val day = Instant.ofEpochMilli(atEpochMillis).atZone(zone).toLocalDate()
        return "saved " + if (day.year == today.year) day.format(DayFormat) else day.format(DayYearFormat)
    }

    /** A highlight style's short name. */
    fun style(style: HighlightStyle): String = when (style) {
        HighlightStyle.Highlight -> "Highlight"
        HighlightStyle.Underline -> "Underline"
        HighlightStyle.Strikethrough -> "Strike"
        HighlightStyle.Squiggly -> "Squiggle"
    }

    // Day first, as in Britain, with three-letter months ("Sep"; the JDK's UK months say "Sept").
    private val DayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.US)
    private val DayYearFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)
}

/** "1 highlight", "1,204 words": a count and its noun, one or many. */
object MarkCounts {
    fun count(n: Int, one: String, many: String = one + "s"): String = if (n == 1) "1 $one" else "${number(n)} $many"

    fun highlights(n: Int): String = count(n, "highlight")

    fun bookmarks(n: Int): String = count(n, "bookmark")

    fun words(n: Int): String = count(n, "word")

    fun books(n: Int): String = count(n, "book")

    private fun number(n: Int): String = String.format(Locale.UK, "%,d", n)
}
