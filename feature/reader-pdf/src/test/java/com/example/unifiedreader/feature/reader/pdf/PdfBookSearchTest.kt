package com.example.unifiedreader.feature.reader.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfBookSearchTest {
    private val page = testPage("The quick brown fox", "jumps over the lazy dog", "and the Fox sleeps")

    @Test
    fun `words match in any case`() {
        val matches = pdfPageMatches(page, pdfSearchPattern("fox"))

        assertEquals(2, matches.size)
        assertEquals("fox", page.text.substring(matches[0].first, matches[0].last + 1))
        assertEquals("Fox", page.text.substring(matches[1].first, matches[1].last + 1))
    }

    @Test
    fun `words match across a line's end, as they read`() {
        val matches = pdfPageMatches(page, pdfSearchPattern("fox   jumps"))

        val match = matches.single()
        assertEquals(2, page.rects(match.first, match.last + 1).size)
    }

    @Test
    fun `what a search types is taken as words, not as a pattern`() {
        assertTrue(pdfPageMatches(page, pdfSearchPattern("fox.*dog")).isEmpty())
        assertTrue(pdfPageMatches(page, pdfSearchPattern("(the")).isEmpty())
    }
}
