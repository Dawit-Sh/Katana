package com.example.unifiedreader.core.model

import java.time.LocalTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceClockTest {
    @After
    fun restoreClock() {
        DeviceClock.uses24Hour = false
    }

    @Test
    fun `reads the 12-hour way unless the device uses 24-hour time`() {
        assertEquals("9:11 PM", DeviceClock.format(LocalTime.of(21, 11)))
        assertEquals("12:05 AM", DeviceClock.format(LocalTime.of(0, 5)))
        DeviceClock.uses24Hour = true
        assertEquals("21:11", DeviceClock.format(LocalTime.of(21, 11)))
        assertEquals("00:05", DeviceClock.format(LocalTime.of(0, 5)))
    }
}
