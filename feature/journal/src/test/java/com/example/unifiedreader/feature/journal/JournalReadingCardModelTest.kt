package com.example.unifiedreader.feature.journal

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalReadingCardModelTest {
    // Wednesday 18 November 2026.
    private val today = LocalDate.of(2026, 11, 18)

    private fun read(minutes: Int) = ReadingCardInput(minutes = minutes, read = true)

    @Test
    fun `fifteen weeks of Monday to Sunday end with this week, the days after today left blank`() {
        val model = readingCardModel(days = emptyMap(), finishedOn = emptyList(), today = today)

        assertEquals(15, model.weeks.size)
        assertTrue(model.weeks.all { it.size == 7 })
        assertEquals(LocalDate.of(2026, 8, 10), model.firstDay)
        assertEquals(DayOfWeek.MONDAY, model.firstDay.dayOfWeek)
        val lastWeek = model.weeks.last()
        assertEquals(LocalDate.of(2026, 11, 16), lastWeek.first().date)
        assertTrue(lastWeek[2].isToday)
        assertTrue(lastWeek.drop(3).all { it.isFuture && !it.read })
        assertEquals(listOf("Aug", "Sep", "Oct", "Nov"), model.monthLabels.map { it.second })
        assertEquals(0, model.streak)
        assertNull(model.bestDay)
    }

    @Test
    fun `the streak runs back from today, or from yesterday while today has no reading yet`() {
        val days = mapOf(
            today to read(18),
            today.minusDays(1) to read(52),
            today.minusDays(2) to read(34),
            today.minusDays(4) to read(10),
        )
        assertEquals(3, readingCardModel(days, emptyList(), today).streak)
        assertEquals(2, readingCardModel(days - today, emptyList(), today).streak)
        assertEquals(4, readingCardModel(days, emptyList(), today).daysRead)
    }

    @Test
    fun `tiles darken with the minutes, and a day read under a minute still shows`() {
        val days = mapOf(
            today.minusDays(1) to ReadingCardInput(minutes = 0, read = true),
            today.minusDays(2) to read(20),
            today.minusDays(3) to read(40),
            today.minusDays(4) to read(70),
            today.minusDays(5) to read(102),
        )
        val levels = readingCardModel(days, emptyList(), today).lastSeven.map { it.level }

        assertEquals(listOf(0, 4, 3, 2, 1, 1, 0), levels)
    }

    @Test
    fun `the last seven days end today with their total, average and the best day`() {
        val days = mapOf(
            LocalDate.of(2026, 11, 12) to read(22),
            LocalDate.of(2026, 11, 14) to read(41),
            LocalDate.of(2026, 11, 15) to read(102),
            LocalDate.of(2026, 11, 16) to read(34),
            LocalDate.of(2026, 11, 17) to read(52),
            LocalDate.of(2026, 11, 18) to read(18),
            // Outside the fifteen weeks: not counted.
            LocalDate.of(2026, 8, 1) to read(300),
        )
        val model = readingCardModel(days, emptyList(), today)

        assertEquals(LocalDate.of(2026, 11, 12), model.lastSeven.first().date)
        assertTrue(model.lastSeven.last().isToday)
        assertEquals(269, model.weekMinutes)
        assertEquals(38, model.averageMinutes)
        assertEquals(LocalDate.of(2026, 11, 15), model.bestDay?.date)
        assertEquals("Best day: Sun 15 Nov, 1h 42m", ReadingCardCopy.bestDay(model.bestDay!!))
        assertEquals("4h 29m", ReadingCardCopy.minutes(model.weekMinutes))
    }

    @Test
    fun `finished books count only inside the fifteen weeks`() {
        val finished = listOf(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 7, 1))

        assertEquals(2, readingCardModel(emptyMap(), finished, today).finished)
    }

    @Test
    fun `the range line names the first Monday`() {
        assertEquals("Last 15 weeks, 10 Aug to today", ReadingCardCopy.range(LocalDate.of(2026, 8, 10)))
    }
}
