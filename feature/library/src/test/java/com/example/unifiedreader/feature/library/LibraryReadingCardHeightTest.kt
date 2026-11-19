package com.example.unifiedreader.feature.library

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryReadingCardHeightTest {
    @Test
    fun `the card is 160 dp at the normal font size and grows with larger text`() {
        assertEquals(160.dp, libraryReadingCardHeight(1f))
        assertEquals(160.dp, libraryReadingCardHeight(0.85f))
        assertEquals(256.dp, libraryReadingCardHeight(2f))
        assertEquals(188.8f, libraryReadingCardHeight(1.3f).value, 0.01f)
    }

    @Test
    fun `its cover stays 80 dp wide`() {
        assertEquals(80f, LibraryReadingCardDefaults.coverWidth.value, 0.01f)
    }
}
