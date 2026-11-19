package com.example.unifiedreader.feature.reader.epub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubBookmarksTest {
    private fun here(pageIndex: Int, totalPages: Int?, href: String? = "OEBPS/c1.xhtml", position: Int = 8) =
        EpubPageHere(href = href, pageIndex = pageIndex, totalPages = totalPages, position = position)

    @Test
    fun `a bookmark made at a page's start is on that page, not the next`() {
        // Made on the fourth of twelve pages: the locator's progression is 3/12, give or take rounding.
        val spot = EpubBookmarkSpot(href = "OEBPS/c1.xhtml", progression = 3.0 / 12 - 1e-9, position = 8)

        assertTrue(spot.isOn(here(pageIndex = 3, totalPages = 12)))
        assertFalse(spot.isOn(here(pageIndex = 2, totalPages = 12)))
        assertFalse(spot.isOn(here(pageIndex = 4, totalPages = 12)))
    }

    @Test
    fun `after the text grows, a bookmark is on the page that now holds its place`() {
        val spot = EpubBookmarkSpot(href = "OEBPS/c1.xhtml", progression = 0.25, position = 8)

        // Twenty pages now: 0.25 falls on the sixth.
        assertTrue(spot.isOn(here(pageIndex = 5, totalPages = 20)))
        assertFalse(spot.isOn(here(pageIndex = 3, totalPages = 20)))
    }

    @Test
    fun `a bookmark in another chapter file is not on this page`() {
        val spot = EpubBookmarkSpot(href = "OEBPS/c2.xhtml", progression = 0.25, position = 8)

        assertFalse(spot.isOn(here(pageIndex = 3, totalPages = 12)))
        assertFalse(spot.isOn(here(pageIndex = 3, totalPages = 12, href = null)))
    }

    @Test
    fun `scrolled, without pages, a bookmark is on the same position`() {
        val spot = EpubBookmarkSpot(href = "OEBPS/c1.xhtml", progression = 0.4, position = 8)

        assertTrue(spot.isOn(here(pageIndex = -1, totalPages = null, position = 8)))
        assertFalse(spot.isOn(here(pageIndex = -1, totalPages = null, position = 9)))
    }

    @Test
    fun `the last page of a chapter keeps a bookmark at its very end`() {
        val spot = EpubBookmarkSpot(href = "OEBPS/c1.xhtml", progression = 1.0, position = 8)

        assertTrue(spot.isOn(here(pageIndex = 11, totalPages = 12)))
    }

    @Test
    fun `scrolling, a bookmark is on screen only while its place is between the screen's top and bottom`() {
        val spot = EpubBookmarkSpot(href = "OEBPS/c1.xhtml", progression = 0.40, position = 8)
        // Readium reports a scrolled chapter as page 0 of 1, which the page test would match everywhere.
        fun scrolled(top: Double, share: Double?) =
            EpubPageHere(href = "OEBPS/c1.xhtml", pageIndex = 0, totalPages = 1, position = 8, scrollTop = top, scrollShare = share)

        assertTrue(spot.isOn(scrolled(top = 0.40, share = 0.1)))
        assertTrue(spot.isOn(scrolled(top = 0.35, share = 0.1)))
        assertFalse(spot.isOn(scrolled(top = 0.0, share = 0.1)))
        assertFalse(spot.isOn(scrolled(top = 0.45, share = 0.1)))
        assertFalse(spot.isOn(scrolled(top = 0.90, share = 0.1)))
    }

    @Test
    fun `scrolling, nothing is on screen until the screen's share of the chapter is known`() {
        val spot = EpubBookmarkSpot(href = "OEBPS/c1.xhtml", progression = 0.40, position = 8)

        val unmeasured = EpubPageHere("OEBPS/c1.xhtml", pageIndex = 0, totalPages = 1, position = 8, scrollTop = 0.40, scrollShare = null)

        assertFalse(spot.isOn(unmeasured))
    }
}
