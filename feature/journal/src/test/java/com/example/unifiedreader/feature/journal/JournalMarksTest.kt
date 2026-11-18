package com.example.unifiedreader.feature.journal

import com.example.unifiedreader.feature.annotations.AnnotatedBook
import com.example.unifiedreader.feature.annotations.AnnotationPlace
import com.example.unifiedreader.feature.annotations.HighlightColor
import com.example.unifiedreader.feature.annotations.HighlightStyle
import com.example.unifiedreader.feature.annotations.ReaderHighlight
import com.example.unifiedreader.feature.annotations.SavedWord
import java.util.Calendar
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JournalMarksTest {
    private val hole = AnnotatedBook(itemId = "grimmory:1", title = "The Hole", author = "Hiroko Oyamada")
    private val dune = AnnotatedBook(itemId = "grimmory:2", title = "Dune", author = "Frank Herbert")
    private val november = periodBounds(JournalPeriod.MONTH, at(18, Calendar.NOVEMBER))

    @Test
    fun `a month counts its highlights and words and quotes the newest two of either kind`() {
        val marks = JournalMarks(
            highlights = listOf(
                highlight("fragments", hole, at(18, Calendar.NOVEMBER, hour = 17), HighlightColor.Blue),
                highlight("the", hole, at(18, Calendar.NOVEMBER, hour = 16)),
                highlight("before November", hole, at(30, Calendar.OCTOBER)),
            ),
            words = listOf(word("solar", hole, at(18, Calendar.NOVEMBER, hour = 18))),
        )

        val month = journalMonthMarks(marks, november.first, november.second)!!

        assertEquals("2 highlights · 1 saved word, all in The Hole", month.countLine)
        assertEquals(
            listOf(
                JournalMarkLine.Word("solar", "The Hole", at(18, Calendar.NOVEMBER, hour = 18)),
                JournalMarkLine.Highlight("fragments", HighlightColor.Blue, "The Hole", at(18, Calendar.NOVEMBER, hour = 17)),
            ),
            month.latest,
        )
    }

    @Test
    fun `the count line names one book, or how many`() {
        val one = JournalMarks(highlights = listOf(highlight("spice", dune, at(3, Calendar.NOVEMBER))))
        assertEquals("1 highlight, in Dune", journalMonthMarks(one, november.first, november.second)?.countLine)

        val two = JournalMarks(
            words = listOf(word("solar", hole, at(4, Calendar.NOVEMBER)), word("melange", dune, at(5, Calendar.NOVEMBER))),
        )
        assertEquals("2 saved words, in 2 books", journalMonthMarks(two, november.first, november.second)?.countLine)
    }

    @Test
    fun `a month without highlights or words has no panel`() {
        val october = JournalMarks(highlights = listOf(highlight("before", hole, at(30, Calendar.OCTOBER))))

        assertNull(journalMonthMarks(october, november.first, november.second))
        assertNull(journalMonthMarks(JournalMarks(), november.first, november.second))
    }

    @Test
    fun `the panel is this month's while it is under way, else it names its month`() {
        val overview = JournalPeriodOverview(
            period = JournalPeriod.MONTH,
            periodStartEpochMillis = november.first,
            periodEndEpochMillis = november.second,
            stats = JournalStats(),
            activityDays = emptyList(),
            finishedItems = emptyList(),
            activeItems = emptyList(),
        )

        assertEquals("Highlights this month", journalHighlightsTitle(overview, at(18, Calendar.NOVEMBER)))
        assertEquals("Highlights in November", journalHighlightsTitle(overview, at(2, Calendar.DECEMBER)))
    }

    private fun highlight(
        text: String,
        book: AnnotatedBook,
        atEpochMillis: Long,
        color: HighlightColor = HighlightColor.Yellow,
    ) = ReaderHighlight(
        id = "h:$text",
        book = book,
        place = AnnotationPlace(href = "chapter.xhtml", locatorJson = "{}", chapterTitle = null, progression = 0.1),
        text = text,
        style = HighlightStyle.Highlight,
        color = color,
        note = null,
        createdAtEpochMillis = atEpochMillis,
        updatedAtEpochMillis = atEpochMillis,
    )

    private fun word(text: String, book: AnnotatedBook, atEpochMillis: Long) = SavedWord(
        id = "w:$text",
        book = book,
        word = text,
        sentence = null,
        place = null,
        note = null,
        createdAtEpochMillis = atEpochMillis,
    )

    private fun at(day: Int, month: Int, hour: Int = 12): Long = Calendar.getInstance(Locale.US).apply {
        clear()
        set(2026, month, day, hour, 0)
    }.timeInMillis
}
