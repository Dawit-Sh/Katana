package com.example.unifiedreader.feature.reader.epub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubPageMovedTest {
    private val opened = EpubShownPage(href = "OEBPS/ch11.xhtml", pageIndex = 2)

    @Test
    fun `the same page laid out again after a touch is not a move`() {
        assertFalse(epubPageMovedSinceOpen(opened, EpubShownPage("OEBPS/ch11.xhtml", 2)))
    }

    @Test
    fun `another page or another chapter is a move`() {
        assertTrue(epubPageMovedSinceOpen(opened, EpubShownPage("OEBPS/ch11.xhtml", 3)))
        assertTrue(epubPageMovedSinceOpen(opened, EpubShownPage("OEBPS/ch12.xhtml", 0)))
    }

    @Test
    fun `a page that can't be numbered counts as moved, as before`() {
        assertTrue(epubPageMovedSinceOpen(opened, EpubShownPage("OEBPS/ch11.xhtml", -1)))
        assertTrue(epubPageMovedSinceOpen(null, EpubShownPage("OEBPS/ch11.xhtml", 2)))
    }
}
