package com.example.unifiedreader.core.model

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PartialMd5Test {
    @Test
    fun `the samples are 1 KB at 0, 1 KB, 4 KB, 16 KB and on by fours, stopping at the file's end`() {
        val ranges = PartialMd5.ranges(66_000)

        assertEquals(listOf(0L, 1_024L, 4_096L, 16_384L, 65_536L), ranges.map { it.first })
        assertEquals(1_023L, ranges.first().last)
        assertEquals("the last sample stops at the end", 65_999L, ranges.last().last)
    }

    @Test
    fun `a file's fingerprint is the MD5 of its samples, as KOReader and Grimmory make it`() {
        val file = ByteArray(5_000) { index -> (index % 251).toByte() }
        val expected = MessageDigest.getInstance("MD5").run {
            update(file, 0, 1_024)
            update(file, 1_024, 1_024)
            update(file, 4_096, 5_000 - 4_096)
            digest().toLowerHex()
        }

        val fingerprint = PartialMd5.of(file.size.toLong()) { start, length -> file.copyOfRange(start.toInt(), start.toInt() + length) }

        assertEquals(expected, fingerprint)
    }

    @Test
    fun `a time more than a day ahead isn't one to go by, a little ahead is`() {
        val now = 1_000_000_000_000L

        assertNull((now + FUTURE_CLOCK_LIMIT_MILLIS + 1).trustedAt(now))
        assertEquals(now + 60_000, (now + 60_000).trustedAt(now))
        assertEquals(now, (now + 3 * FUTURE_CLOCK_LIMIT_MILLIS).clampedToNowIfBeyondLimit(now))
    }
}
