package com.example.unifiedreader.feature.annotations

import com.example.unifiedreader.core.model.DeviceClock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class BookmarksCopyTest {
    // These read times the 24-hour way; DeviceClockTest covers the 12-hour default.
    @Before
    fun useTwentyFourHourClock() {
        DeviceClock.uses24Hour = true
    }

    @After
    fun restoreClock() {
        DeviceClock.uses24Hour = false
    }

    private val zone = ZoneOffset.ofHours(3)
    private val today = LocalDate.of(2026, 11, 18)

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute).toInstant(zone).toEpochMilli()

    @Test
    fun `a mark says today with its time, yesterday, the day, or the day and year`() {
        assertEquals("today, 18:38", BookmarksCopy.moment(at(2026, 11, 18, 18, 38), today, zone))
        assertEquals("yesterday", BookmarksCopy.moment(at(2026, 11, 17), today, zone))
        assertEquals("16 Sep", BookmarksCopy.moment(at(2026, 9, 16), today, zone))
        assertEquals("9 Nov 2025", BookmarksCopy.moment(at(2025, 11, 9), today, zone))
        assertEquals("Last marked today, 08:05", BookmarksCopy.lastMarked(at(2026, 11, 18, 8, 5), today, zone))
        assertEquals("saved 18 Nov", BookmarksCopy.saved(at(2026, 11, 18), today, zone))
    }

    @Test
    fun `counts say one or many, with a thousands comma`() {
        assertEquals("1 highlight", MarkCounts.highlights(1))
        assertEquals("1,204 words", MarkCounts.words(1204))
        assertEquals("Continue · p. 8 of 78", BookmarksCopy.continueAt("p. 8 of 78"))
        assertEquals("Continue", BookmarksCopy.continueAt(null))
    }
}
