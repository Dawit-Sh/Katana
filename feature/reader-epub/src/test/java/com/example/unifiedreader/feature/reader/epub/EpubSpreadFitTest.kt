package com.example.unifiedreader.feature.reader.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubSpreadFitTest {
    @Test
    fun `a spread needs the 960 dp Readium lays two columns in`() {
        // The Fire 7 (2019) in landscape.
        assertTrue(epubShowsSpread(1024))
        assertTrue(epubShowsSpread(960))
        // A Pixel or a Galaxy A phone in landscape, and any phone in portrait.
        assertFalse(epubShowsSpread(915))
        assertFalse(epubShowsSpread(853))
        assertFalse(epubShowsSpread(384))
    }

    @Test
    fun `a spread that can't fit is shown as one page, every other layout as chosen`() {
        assertEquals(EpubReaderLayoutMode.SinglePage, EpubReaderLayoutMode.DoublePage.fitting(spreadFits = false))
        assertEquals(EpubReaderLayoutMode.DoublePage, EpubReaderLayoutMode.DoublePage.fitting(spreadFits = true))
        assertEquals(EpubReaderLayoutMode.Scroll, EpubReaderLayoutMode.Scroll.fitting(spreadFits = false))
        assertEquals(EpubReaderLayoutMode.SinglePage, EpubReaderLayoutMode.SinglePage.fitting(spreadFits = true))
    }
}
