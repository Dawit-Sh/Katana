package com.example.unifiedreader.feature.reader.epub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubBookmarkCornerTest {
    // A 1080 px wide reader at 2.625 px per dp: the corner is the last 294 px, down to 126 px below the page top.
    private val width = 1080f
    private val density = 2.625f

    @Test
    fun `the top-right corner bookmarks, the ribbon and the strip above the text included`() {
        assertTrue(isEpubBookmarkCorner(x = 1060f, yFromPageTop = 10f, width = width, density = density))
        // Where the ribbon hangs, 72 to 98 dp from the edge.
        assertTrue(isEpubBookmarkCorner(x = 1080f - 85f * density, yFromPageTop = 40f, width = width, density = density))
        // On the strip above the text.
        assertTrue(isEpubBookmarkCorner(x = 1000f, yFromPageTop = -60f, width = width, density = density))
    }

    @Test
    fun `below the corner, or left of it, a tap turns the page as before`() {
        assertFalse(isEpubBookmarkCorner(x = 1060f, yFromPageTop = 130f, width = width, density = density))
        assertFalse(isEpubBookmarkCorner(x = 780f, yFromPageTop = 10f, width = width, density = density))
        assertFalse(isEpubBookmarkCorner(x = 0f, yFromPageTop = 0f, width = 0f, density = density))
    }
}
