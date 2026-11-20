package com.example.unifiedreader

import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.data.grimmory.GrimmoryBookmark
import com.example.unifiedreader.data.grimmory.GrimmoryBookmarkPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where Grimmory keeps page bookmarks: a PDF's in pageNumber, a comic's page written in cfi (its own readers' ways). */
class GrimmoryPageBookmarksTest {
    private fun bookmark(cfi: String? = null, pageNumber: Int? = null) = GrimmoryBookmark(
        id = "5",
        bookId = "34",
        cfi = cfi,
        pageNumber = pageNumber,
        title = "Page 12",
        notes = null,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 2L,
    )

    @Test
    fun `a PDF's bookmark is its page number, a comic's the page in its CFI`() {
        assertEquals("12", bookmark(pageNumber = 12).toRemoteBookmark(FormatType.PDF)?.place)
        assertEquals("7", bookmark(cfi = "7").toRemoteBookmark(FormatType.CBZ)?.place)
        assertEquals("7", bookmark(cfi = "7").toRemoteBookmark(FormatType.CBR)?.place)
    }

    @Test
    fun `places another format of the same book uses, or an audiobook's, are left alone`() {
        assertNull(bookmark(cfi = "epubcfi(/6/4!/4/2,/1:0,/1:5)").toRemoteBookmark(FormatType.PDF))
        assertNull(bookmark(cfi = "7").toRemoteBookmark(FormatType.EPUB))
        assertNull(bookmark(pageNumber = 3).toRemoteBookmark(FormatType.EPUB))
        assertNull(bookmark().toRemoteBookmark(FormatType.CBZ))
        assertNull(bookmark(pageNumber = 3).toRemoteBookmark(FormatType.DJVU))
    }

    @Test
    fun `a new bookmark goes to Grimmory as its own readers keep it`() {
        assertEquals(GrimmoryBookmarkPlace.PdfPage(12), grimmoryPlaceOf("12", FormatType.PDF))
        assertEquals(GrimmoryBookmarkPlace.Cfi("7"), grimmoryPlaceOf("7", FormatType.CBZ))
        assertEquals(GrimmoryBookmarkPlace.Cfi("epubcfi(/6/4!/4/2)"), grimmoryPlaceOf("epubcfi(/6/4!/4/2)", FormatType.EPUB))
        assertNull(grimmoryPlaceOf("12", FormatType.DJVU))
    }

    @Test
    fun `Grimmory's EPUBs, PDFs and comics sync their bookmarks, DjVu and converted books stay here`() {
        fun grimmory(format: FormatType) = ReadableItem(
            id = "grimmory:item:1",
            sourceType = SourceType.GRIMMORY,
            libraryId = "l",
            remoteId = "1",
            title = "t",
            format = format,
        )

        assertTrue(grimmory(FormatType.PDF).syncsBookmarksWithGrimmory())
        assertTrue(grimmory(FormatType.CBZ).syncsBookmarksWithGrimmory())
        assertTrue(grimmory(FormatType.EPUB).syncsBookmarksWithGrimmory())
        assertFalse(grimmory(FormatType.DJVU).syncsBookmarksWithGrimmory())
        assertFalse(grimmory(FormatType.MOBI).syncsBookmarksWithGrimmory())
        assertFalse(grimmory(FormatType.PDF).copy(sourceType = SourceType.LOCAL, remoteId = null).syncsBookmarksWithGrimmory())
    }
}
