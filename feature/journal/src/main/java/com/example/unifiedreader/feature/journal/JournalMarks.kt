package com.example.unifiedreader.feature.journal

import com.example.unifiedreader.feature.annotations.HighlightColor
import com.example.unifiedreader.feature.annotations.ReaderHighlight
import com.example.unifiedreader.feature.annotations.SavedWord

/** The reader's highlights and saved words, every book's, as the journal gets them. */
data class JournalMarks(
    val highlights: List<ReaderHighlight> = emptyList(),
    val words: List<SavedWord> = emptyList(),
)

/** One of a month's latest marks: a highlight's words or a saved word, and the book it is from. */
internal sealed interface JournalMarkLine {
    val bookTitle: String
    val atEpochMillis: Long

    data class Highlight(
        val text: String,
        val color: HighlightColor,
        override val bookTitle: String,
        override val atEpochMillis: Long,
    ) : JournalMarkLine

    data class Word(
        val word: String,
        override val bookTitle: String,
        override val atEpochMillis: Long,
    ) : JournalMarkLine
}

/** A month's highlights and saved words: how many, from which books, and the [latest] few. */
internal data class JournalMonthMarks(
    val highlightCount: Int,
    val wordCount: Int,
    val bookTitles: List<String>,
    val latest: List<JournalMarkLine>,
) {
    /** "2 highlights · 1 saved word, all in The Hole", "1 highlight, in Dune", "5 highlights, in 3 books". */
    val countLine: String
        get() {
            val counts = listOfNotNull(
                highlightCount.takeIf { it > 0 }?.counted("highlight"),
                wordCount.takeIf { it > 0 }?.counted("saved word"),
            ).joinToString(" · ")
            val where = when {
                bookTitles.size > 1 -> "in ${bookTitles.size} books"
                highlightCount + wordCount > 1 -> "all in ${bookTitles.single()}"
                else -> "in ${bookTitles.single()}"
            }
            return "$counts, $where"
        }
}

/**
 * The highlights and saved words made in [startEpochMillis, endEpochMillis), counted, with the
 * [latestCount] newest of both kinds together; null when there are none.
 */
internal fun journalMonthMarks(
    marks: JournalMarks,
    startEpochMillis: Long,
    endEpochMillis: Long,
    latestCount: Int = JournalLatestMarkCount,
): JournalMonthMarks? {
    val inMonth = { at: Long -> at in startEpochMillis until endEpochMillis }
    val highlights = marks.highlights.filter { highlight -> inMonth(highlight.createdAtEpochMillis) }
    val words = marks.words.filter { word -> inMonth(word.createdAtEpochMillis) }
    if (highlights.isEmpty() && words.isEmpty()) return null
    val lines = highlights.map { highlight ->
        JournalMarkLine.Highlight(highlight.text, highlight.color, highlight.book.title, highlight.createdAtEpochMillis)
    } + words.map { word -> JournalMarkLine.Word(word.word, word.book.title, word.createdAtEpochMillis) }
    val newestFirst = lines.sortedByDescending(JournalMarkLine::atEpochMillis)
    return JournalMonthMarks(
        highlightCount = highlights.size,
        wordCount = words.size,
        bookTitles = newestFirst.map(JournalMarkLine::bookTitle).distinct(),
        latest = newestFirst.take(latestCount),
    )
}

/** How many of a month's marks its panel quotes. */
internal const val JournalLatestMarkCount: Int = 2

/** "Highlights this month" for the month under way, "Highlights in October" for one before it. */
internal fun journalHighlightsTitle(overview: JournalPeriodOverview, nowEpochMillis: Long): String =
    if (nowEpochMillis in overview.periodStartEpochMillis until overview.periodEndEpochMillis) {
        "Highlights this month"
    } else {
        "Highlights in ${overview.periodName()}"
    }
