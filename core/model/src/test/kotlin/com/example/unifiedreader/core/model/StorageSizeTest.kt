package com.example.unifiedreader.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageSizeTest {
    @Test
    fun `sizes read in the largest unit, one decimal below ten`() {
        assertEquals("0 B", formatStorageSize(0))
        assertEquals("999 B", formatStorageSize(999))
        assertEquals("640 KB", formatStorageSize(640_000))
        assertEquals("2.1 MB", formatStorageSize(2_100_000))
        assertEquals("8.5 MB", formatStorageSize(8_480_000))
        assertEquals("310 MB", formatStorageSize(310_400_000))
        assertEquals("1.5 GB", formatStorageSize(1_480_000_000))
        assertEquals("12 GB", formatStorageSize(12_300_000_000))
    }

    @Test
    fun `a size that rounds to ten drops its decimal`() {
        assertEquals("10 MB", formatStorageSize(9_970_000))
    }

    @Test
    fun `a negative size reads as nothing`() {
        assertEquals("0 B", formatStorageSize(-5))
    }
}
