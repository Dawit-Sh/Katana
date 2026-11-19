package com.example.unifiedreader.feature.reader.epub

import org.junit.Assert.assertEquals
import com.example.unifiedreader.feature.reader.preferences.ReaderPagesMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubReaderPreferencesModelTest {

    @Test
    fun `the orientation default turns pages, two at a time on Auto, whatever the stored mode`() {
        EpubReaderLayoutMode.entries.forEach { mode ->
            assertEquals(mode.name, ReaderPagesMode.Turn, epubPagesMode(useOrientationLayoutDefault = true, layoutMode = mode))
            assertEquals(mode.name, EpubSpreadChoice.Auto, epubSpreadChoice(useOrientationLayoutDefault = true, layoutMode = mode))
        }
    }

    @Test
    fun `only the scroll layout scrolls, and two pages doesn't apply to it`() {
        assertEquals(ReaderPagesMode.Scroll, epubPagesMode(useOrientationLayoutDefault = false, layoutMode = EpubReaderLayoutMode.Scroll))
        assertNull(epubSpreadChoice(useOrientationLayoutDefault = false, layoutMode = EpubReaderLayoutMode.Scroll))
        assertEquals(ReaderPagesMode.Turn, epubPagesMode(useOrientationLayoutDefault = false, layoutMode = EpubReaderLayoutMode.SinglePage))
        assertEquals(ReaderPagesMode.Turn, epubPagesMode(useOrientationLayoutDefault = false, layoutMode = EpubReaderLayoutMode.DoublePage))
    }

    @Test
    fun `two pages maps each fixed page layout and back`() {
        assertEquals(EpubSpreadChoice.Off, epubSpreadChoice(false, EpubReaderLayoutMode.SinglePage))
        assertEquals(EpubSpreadChoice.On, epubSpreadChoice(false, EpubReaderLayoutMode.DoublePage))
        assertEquals(EpubReaderLayoutMode.SinglePage, EpubSpreadChoice.Off.layoutMode)
        assertEquals(EpubReaderLayoutMode.DoublePage, EpubSpreadChoice.On.layoutMode)
        assertNull(EpubSpreadChoice.Auto.layoutMode)
    }

    @Test
    fun `text size steps by a tenth on clean values and stops at the ends`() {
        assertEquals(1.1, stepEpubFontSize(1.0, +1), 1e-9)
        assertEquals(0.9, stepEpubFontSize(1.0, -1), 1e-9)
        assertEquals(1.2, stepEpubFontSize(1.14, +1), 1e-9)
        assertEquals(EpubFontSizeMax, stepEpubFontSize(EpubFontSizeMax, +1), 1e-9)
        assertEquals(EpubFontSizeMin, stepEpubFontSize(EpubFontSizeMin, -1), 1e-9)
        assertEquals(EpubFontSizeMax, stepEpubFontSize(2.35, +1), 1e-9)
    }

    @Test
    fun `stepper buttons disable at the range ends only`() {
        assertTrue(canStepEpubFontSize(1.0, +1))
        assertTrue(canStepEpubFontSize(1.0, -1))
        assertFalse(canStepEpubFontSize(EpubFontSizeMax, +1))
        assertTrue(canStepEpubFontSize(EpubFontSizeMax, -1))
        assertFalse(canStepEpubFontSize(EpubFontSizeMin, -1))
        assertTrue(canStepEpubFontSize(EpubFontSizeMin, +1))
    }

    @Test
    fun `alignment segments are one short word each`() {
        assertEquals(listOf("Auto", "Left", "Justify"), EpubReaderTextAlign.entries.map(::epubTextAlignLabel))
    }

    @Test
    fun `typography mode wording`() {
        assertEquals("Book's own", epubTypographyModeLabel(EpubTypographyMode.Original))
        assertEquals("Custom", epubTypographyModeLabel(EpubTypographyMode.Custom))
    }

    @Test
    fun `a value between steps shows as the nearest step`() {
        assertEquals("Normal", EpubMarginSteps.nearest(1.4).label)
        assertEquals("Wide", EpubMarginSteps.nearest(1.6).label)
        assertEquals("Small", EpubParagraphSpacingSteps.nearest(0.4).label)
        assertEquals("Large", EpubParagraphIndentSteps.nearest(2.6).label)
    }

    @Test
    fun `line height moves in tenths inside its range and reads as a multiple`() {
        assertEquals(1.5, snapEpubLineHeight(1.54), 1e-9)
        assertEquals(EpubLineHeightMin, snapEpubLineHeight(0.4), 1e-9)
        assertEquals(EpubLineHeightMax, snapEpubLineHeight(3.0), 1e-9)
        assertEquals("1.5\u00d7", formatEpubLineHeight(1.5))
    }
}
