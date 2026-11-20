package com.example.unifiedreader.feature.journal

import com.example.unifiedreader.core.model.DeviceClock
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class JournalTimeFormatTest {
    private val wasTwentyFourHour = DeviceClock.uses24Hour
    private val evening = LocalDateTime.of(2026, 11, 20, 21, 4).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @After
    fun restoreClock() {
        DeviceClock.uses24Hour = wasTwentyFourHour
    }

    @Test
    fun `an event's time follows a 12-hour clock`() {
        DeviceClock.uses24Hour = false

        assertEquals("Nov 20, 9:04 PM", formatDateTime(evening))
    }

    @Test
    fun `an event's time follows a 24-hour clock`() {
        DeviceClock.uses24Hour = true

        assertEquals("Nov 20, 21:04", formatDateTime(evening))
    }
}
