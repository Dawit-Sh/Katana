package com.example.unifiedreader.feature.reader.core

import com.example.unifiedreader.feature.reader.preferences.ComicReadingDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderDockProgressTest {
    @Test
    fun `rtl progress is drawn from the right edge`() {
        assertEquals(
            ReaderProgressFillRange(
                startFraction = 0.75f,
                endFraction = 1f,
            ),
            resolveReaderProgressFillRange(
                progress = 0.25f,
                readingDirection = ComicReadingDirection.RTL,
            ),
        )
    }

    @Test
    fun `rtl progress selection maps right edge to start of book`() {
        assertEquals(
            0f,
            resolveReaderProgressSelection(
                horizontalFraction = 1f,
                readingDirection = ComicReadingDirection.RTL,
            ),
        )
    }

    @Test
    fun `ltr progress selection remains left to right`() {
        assertEquals(
            0.25f,
            resolveReaderProgressSelection(
                horizontalFraction = 0.25f,
                readingDirection = ComicReadingDirection.LTR,
            ),
        )
    }

    @Test
    fun `the knob stays inside the track`() {
        assertEquals(4.5f, readerDockKnobCenter(width = 300f, progress = 0f, knobWidth = 9f))
        assertEquals(150f, readerDockKnobCenter(width = 300f, progress = 0.5f, knobWidth = 9f))
        assertEquals(295.5f, readerDockKnobCenter(width = 300f, progress = 1f, knobWidth = 9f))
    }

    @Test
    fun `a track squeezed narrower than the knob doesn't crash the reader`() {
        // 200% font on a 320 dp phone left the slider 0 px wide: coerceIn(4.5, -4.5) threw and closed the book.
        assertEquals(0f, readerDockKnobCenter(width = 0f, progress = 0.3f, knobWidth = 9f))
        assertEquals(3f, readerDockKnobCenter(width = 6f, progress = 1f, knobWidth = 9f))
    }

    @Test
    fun `the labels stay beside the slider while it keeps 120 dp`() {
        // A 320 dp row at 2.25 px per dp: "3%" and "p. 11 / 357" at normal size leave the slider most of it.
        assertEquals(
            false,
            readerDockLabelsAboveSlider(width = 600, percentWidth = 60, positionWidth = 180, gap = 27, minSliderWidth = 270),
        )
    }

    @Test
    fun `very large labels go above a slider as wide as the dock`() {
        // 200% text: the labels take nearly all the row, as on the phone that crashed.
        assertEquals(
            true,
            readerDockLabelsAboveSlider(width = 540, percentWidth = 120, positionWidth = 380, gap = 27, minSliderWidth = 270),
        )
        assertEquals(true, readerDockLabelsAboveSlider(width = 400, percentWidth = 60, positionWidth = 50, gap = 27, minSliderWidth = 270))
    }
}
