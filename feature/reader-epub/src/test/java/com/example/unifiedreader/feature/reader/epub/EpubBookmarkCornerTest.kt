package com.example.unifiedreader.feature.reader.epub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubBookmarkCornerTest {
    @Test
    fun `only the top-right square bookmarks`() {
        val width = 1000f
        val corner = 150f
        assertTrue(isEpubBookmarkCorner(x = 990f, y = 10f, width = width, cornerSize = corner))
        assertTrue(isEpubBookmarkCorner(x = 850f, y = 150f, width = width, cornerSize = corner))
        // Below the corner, or left of it, a tap turns the page as before.
        assertFalse(isEpubBookmarkCorner(x = 990f, y = 151f, width = width, cornerSize = corner))
        assertFalse(isEpubBookmarkCorner(x = 849f, y = 10f, width = width, cornerSize = corner))
        assertFalse(isEpubBookmarkCorner(x = 10f, y = 10f, width = width, cornerSize = corner))
        assertFalse(isEpubBookmarkCorner(x = 0f, y = 0f, width = 0f, cornerSize = corner))
    }
}
