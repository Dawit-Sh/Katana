package com.example.unifiedreader.feature.journal

import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.feature.library.ItemProgressSummary
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalReadingCardModelTest {
    // Saturday 21 November 2026.
    private val today = LocalDate.of(2026, 11, 21)
    private val goal = 30

    private fun read(minutes: Int) = ReadingCardInput(minutes = minutes, read = true)

    @Test
    fun `sixteen weeks of Monday to Sunday end with this week, the days after today to come`() {
        val model = readingCardModel(days = emptyMap(), today = today, goalMinutes = goal)

        assertEquals(16, model.weeks.size)
        assertTrue(model.weeks.all { it.size == 7 })
        val first = model.weeks.first().first().date
        assertEquals(LocalDate.of(2026, 8, 3), first)
        assertEquals(DayOfWeek.MONDAY, first.dayOfWeek)
        val lastWeek = model.weeks.last()
        assertEquals(LocalDate.of(2026, 11, 16), lastWeek.first().date)
        assertTrue(lastWeek[5].isToday)
        assertTrue(lastWeek[6].isFuture)
        assertEquals(listOf("Aug", "Sep", "Oct", "Nov"), readingCardMonthLabels(model.weeks).map { it.second })
        assertEquals(0, model.todayMinutes)
    }

    @Test
    fun `days step by the daily goal, and a day read under a minute still shows`() {
        val days = mapOf(
            today to read(72),
            today.minusDays(1) to ReadingCardInput(minutes = 0, read = true),
            today.minusDays(2) to read(10),
            today.minusDays(3) to read(20),
            today.minusDays(4) to read(30),
            today.minusDays(5) to read(59),
        )
        val week = readingCardModel(days, today, goal).weeks.last()

        // Monday 16 to Sunday 22: 59, 30, 20, 10, read without minutes, 72 today, Sunday to come.
        assertEquals(listOf(3, 3, 2, 1, 1, 4, 0), week.map { it.step })
        assertEquals(72, readingCardModel(days, today, goal).todayMinutes)
        // A longer goal moves the same days down.
        assertEquals(listOf(2, 2, 1, 1, 1, 3, 0), readingCardModel(days, today, goalMinutes = 60).weeks.last().map { it.step })
    }

    @Test
    fun `today's time reads in minutes under an hour, in hours and minutes after`() {
        assertEquals("0 min", readingTodayTime(0))
        assertEquals("22 min", readingTodayTime(22))
        assertEquals("59 min", readingTodayTime(59))
        assertEquals("1 h", readingTodayTime(60))
        assertEquals("1 h 05", readingTodayTime(65))
        assertEquals("1 h 12", readingTodayTime(72))
        assertEquals("3 h 40", readingTodayTime(220))
    }

    @Test
    fun `the book's line is its chapter and how far, or only how far`() {
        assertEquals("Stave Three · 34%", readingCardBookDetail(progress(0.34f, chapterTitle = "Stave Three", chapter = 3)))
        assertEquals("Chapter 9 · 70%", readingCardBookDetail(progress(0.7f, chapter = 9)))
        assertEquals("34%", readingCardBookDetail(progress(0.34f)))
        assertEquals("34%", readingCardBookDetail(progress(0.34f, chapterTitle = "  ")))
        assertNull(readingCardBookDetail(null))
    }

    @Test
    fun `the heat map fills its width with up to sixteen weeks of squares`() {
        val wide = heatMapFit(width = 300f, height = Float.MAX_VALUE, weeks = 16, gap = 3f, cells = 8f..18f)
        assertEquals(16, wide.weeks)
        assertEquals(15.9375f, wide.cell, 0.001f)
        // A wider menu stops at the biggest squares.
        assertEquals(18f, heatMapFit(400f, Float.MAX_VALUE, 16, 3f, 8f..18f).cell, 0.001f)
    }

    @Test
    fun `a shorter height makes the squares smaller, all sixteen weeks kept, down to the smallest`() {
        val tenHigh = heatMapFit(width = 300f, height = heatMapGridHeight(10f, 3f), weeks = 16, gap = 3f, cells = 8f..18f)
        assertEquals(HeatMapFit(weeks = 16, cell = 10f), tenHigh)
        assertEquals(HeatMapFit(weeks = 16, cell = 8f), heatMapFit(300f, 30f, 16, 3f, 8f..18f))
        assertEquals(88f, heatMapGridHeight(10f, 3f), 0.001f)
    }

    @Test
    fun `only a width too narrow for the smallest squares drops the oldest weeks`() {
        val narrow = heatMapFit(width = 150f, height = Float.MAX_VALUE, weeks = 16, gap = 3f, cells = 8f..18f)
        assertEquals(13, narrow.weeks)
        assertEquals(8.77f, narrow.cell, 0.01f)
    }

    @Test
    fun `the book being read is the one read last that isn't finished, from any source`() {
        fun book(id: String, source: SourceType) =
            ReadableItem(id = id, sourceType = source, libraryId = "1", title = id, format = FormatType.EPUB)
        val carol = book("carol", SourceType.GRIMMORY)
        val hole = book("hole", SourceType.LOCAL)
        val done = book("done", SourceType.GRIMMORY)
        val items = listOf(carol, hole, done)
        val progress = mapOf(
            "carol" to progress(0.34f).copy(lastReadAtEpochMillis = 100L),
            "hole" to progress(0.7f).copy(lastReadAtEpochMillis = 200L),
            "done" to progress(1f).copy(lastReadAtEpochMillis = 300L, isFinished = true),
        )
        assertEquals(hole, readingCardBook(items, progress)?.item)
        assertEquals(carol, readingCardBook(items - hole, progress)?.item)
        assertNull(readingCardBook(items, progress - "carol" - "hole"))
        assertNull(readingCardBook(emptyList(), progress))
    }

    private fun progress(fraction: Float, chapterTitle: String? = null, chapter: Int? = null) =
        ItemProgressSummary(label = "", fraction = fraction, chapter = chapter, chapterTitle = chapterTitle)
}
