package com.example.unifiedreader.feature.library

import com.example.unifiedreader.core.model.BookMetadata
import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LibraryFormatLabelTest {
    private val epub = ReadableItem(
        id = "local:a",
        sourceType = SourceType.LOCAL,
        libraryId = "local-imports",
        title = "Pride and Prejudice",
        format = FormatType.EPUB,
        bookMetadata = BookMetadata("Jane Austen"),
    )
    private val azw3 = epub.copy(id = "local:b", format = FormatType.AZW3)

    @Test
    fun `the same title in two formats differs on the grid tile and in search`() {
        assertEquals("EPUB · 3%", gridTileMetaLabel(epub, progressLabel = "3%"))
        assertEquals("AZW3", gridTileMetaLabel(azw3, progressLabel = null))
        assertNotEquals(librarySearchResultLine(epub), librarySearchResultLine(azw3))
        assertEquals("Jane Austen · AZW3", librarySearchResultLine(azw3))
    }

    @Test
    fun `a series keeps its issue count without a format, and DjVu is named as it is written`() {
        assertEquals("12 issues", gridTileMetaLabel(epub.copy(format = FormatType.CBZ), progressLabel = null, seriesIssueCount = 12))
        val djvu = epub.copy(format = FormatType.DJVU)
        assertEquals("DjVu · 150 pages", gridTileMetaLabel(djvu, progressLabel = "3%", lengthLabel = "150 pages"))
        assertEquals("DjVu", librarySearchResultLine(epub.copy(format = FormatType.DJVU, bookMetadata = null)))
    }
}
