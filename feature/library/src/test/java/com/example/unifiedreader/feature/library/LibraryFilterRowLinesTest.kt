package com.example.unifiedreader.feature.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryFilterRowLinesTest {
    // Pixels at 2x: a 360 dp phone leaves 328 dp (656 px) between the gutters.
    private fun lines(heading: Int?, filters: Int, sort: Int, width: Int = 656) =
        libraryFilterRowLines(width = width, headingSpace = heading?.let { it + 24 }, chipsWidth = filters + 16 + sort)

    @Test
    fun `heading, Filters and sort share one line while they fit`() {
        // "All titles 137", Filters and "Last read" at the normal font size.
        assertEquals(LibraryFilterRowLines.One, lines(heading = 260, filters = 182, sort = 170))
        assertEquals(LibraryFilterRowLines.One, lines(heading = null, filters = 182, sort = 170))
    }

    @Test
    fun `at a large font size the chips go under the heading instead of losing Filters`() {
        // 200%: the heading alone takes half the line.
        assertEquals(LibraryFilterRowLines.ChipsUnderHeading, lines(heading = 346, filters = 280, sort = 300))
    }

    @Test
    fun `sort gets its own line when it and Filters don't fit side by side`() {
        assertEquals(LibraryFilterRowLines.SortOnItsOwn, lines(heading = 346, filters = 300, sort = 380))
        // A series has no heading: Filters on one line, sort on the next.
        assertEquals(LibraryFilterRowLines.SortOnItsOwn, lines(heading = null, filters = 300, sort = 380))
    }

    @Test
    fun `the edge counts as fitting`() {
        assertEquals(LibraryFilterRowLines.One, lines(heading = 100, filters = 200, sort = 316))
        assertEquals(LibraryFilterRowLines.ChipsUnderHeading, lines(heading = 100, filters = 200, sort = 317))
    }
}
