package com.example.unifiedreader.core.djvu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DjvuPageTextTest {
    @Test
    fun `words come with their lines and boxes`() {
        val text = "Alice’s café"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val split = "Alice’s".toByteArray(Charsets.UTF_8).size
        val numbers = intArrayOf(
            1000, 1600, 1,
            0, 0, split, 100, 1400, 300, 1450,
            1, split + 1, bytes.size, 100, 1300, 220, 1350,
        )

        val page = djvuPageTextOf(arrayOf(bytes, numbers))!!

        assertEquals(1000, page.width)
        assertEquals(1600, page.height)
        assertEquals(1, page.rotation)
        assertEquals(
            listOf(DjvuTextWord("Alice’s", 0, 100, 1400, 300, 1450), DjvuTextWord("café", 1, 100, 1300, 220, 1350)),
            page.words,
        )
    }

    @Test
    fun `a word outside its bytes or without a box is left out`() {
        val bytes = "one two".toByteArray(Charsets.UTF_8)
        val numbers = intArrayOf(
            500, 800, 0,
            0, 0, 3, 10, 700, 60, 720,
            0, 4, 99, 70, 700, 120, 720,
            0, 4, 7, 70, 720, 120, 700,
        )

        val page = djvuPageTextOf(arrayOf(bytes, numbers))!!

        assertEquals(listOf("one"), page.words.map(DjvuTextWord::text))
    }

    @Test
    fun `nothing usable gives no text`() {
        assertNull(djvuPageTextOf(arrayOf(ByteArray(0), intArrayOf(500, 800, 0))))
        assertNull(djvuPageTextOf(arrayOf(null, null)))
        assertNull(djvuPageTextOf(arrayOf("x".toByteArray(), intArrayOf(0, 800, 0, 0, 0, 1, 1, 1, 2, 2))))
        assertNull(djvuPageTextOf(emptyArray()))
    }
}
