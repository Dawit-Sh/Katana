package com.example.unifiedreader.feature.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryProgressTest {
    @Test
    fun `percentages round as the readers do`() {
        // Page 78 of 202 is 38.6%: the comic reader says 39%, so the card does too.
        assertEquals("39%", ItemProgressSummary(label = "", fraction = 78f / 202f).percentLabel)
        assertEquals("0%", ItemProgressSummary(label = "", fraction = 0f).percentLabel)
        assertEquals("100%", ItemProgressSummary(label = "", fraction = 1f).percentLabel)
    }

    @Test
    fun `a place with no saved page shows the page the reader opens on`() {
        // The Hole on his Pixel: 5/78 of the way in is the start of page 6, which the card called page 4.
        val hole = ItemProgressSummary(label = "", fraction = 5f / 78f, pageCount = 78)
        assertEquals(6, hole.shownPage)
        assertEquals("6/78", hole.pageProgressLabel)
        assertEquals("Page 6 of 78", libraryReadingCardLines(null, hole, null, tall = false).single())
        // A share read in the middle of a page stays on that page.
        assertEquals(7, ItemProgressSummary(label = "", fraction = 0.077f, pageCount = 78).shownPage)
        assertEquals(1, ItemProgressSummary(label = "", fraction = 0f, pageCount = 78).shownPage)
        assertEquals(78, ItemProgressSummary(label = "", fraction = 1f, pageCount = 78).shownPage)
        // Every page start, in a short book and a long one.
        listOf(22, 78, 589, 4000).forEach { pages ->
            (1..pages).forEach { page ->
                val start = ItemProgressSummary(label = "", fraction = (page - 1).toFloat() / pages, pageCount = pages)
                assertEquals("page $page of $pages", page, start.shownPage)
            }
        }
    }

    @Test
    fun `a saved page always wins`() {
        assertEquals(5, ItemProgressSummary(label = "", fraction = 0.9f, currentPage = 5, pageCount = 8).shownPage)
        assertEquals(null, ItemProgressSummary(label = "", fraction = 0.5f).shownPage)
    }
}
