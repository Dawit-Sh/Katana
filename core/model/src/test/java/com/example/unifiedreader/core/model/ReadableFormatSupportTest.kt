package com.example.unifiedreader.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadableFormatSupportTest {
    @Test
    fun `readable format classification includes all active readers`() {
        assertTrue(FormatType.EPUB.isBookReaderFormat())
        assertTrue(FormatType.PDF.isBookReaderFormat())
        assertTrue(FormatType.CBZ.isComicReaderFormat())
        assertTrue(FormatType.CBR.isComicReaderFormat())
        assertEquals(FormatType.entries.toSet(), AppReadableFormats)
    }

    @Test
    fun `MOBI AZW AZW3 and FB2 open in the EPUB reader and DjVu in the page reader`() {
        listOf(FormatType.MOBI, FormatType.AZW, FormatType.AZW3, FormatType.FB2).forEach { format ->
            assertTrue(format.opensInEpubReader())
            assertTrue(format.isConvertedToEpub())
            assertTrue(format.isBookReaderFormat())
        }
        assertTrue(FormatType.EPUB.opensInEpubReader())
        assertFalse(FormatType.EPUB.isConvertedToEpub())
        assertTrue(FormatType.DJVU in PageImageBookFormats)
        assertFalse(FormatType.DJVU.opensInEpubReader())
        assertFalse(FormatType.PDF.opensInEpubReader())
    }

    @Test
    fun `readable format resolves from type extension or file name`() {
        assertEquals(FormatType.EPUB, readableFormatFromTypeOrExtension(type = "EPUB", extension = "pdf"))
        assertEquals(FormatType.PDF, readableFormatFromTypeOrExtension(type = null, extension = "pdf"))
        assertEquals(FormatType.CBZ, readableFormatFromTypeOrExtension(type = null, extension = "cbz"))
        assertEquals(FormatType.CBZ, readableFormatFromTypeOrExtension(type = null, extension = "zip"))
        assertEquals(FormatType.CBR, readableFormatFromTypeOrExtension(type = null, extension = "cbr"))
        assertEquals(FormatType.CBR, readableFormatFromTypeOrExtension(type = null, extension = "rar"))
        assertEquals(FormatType.CBZ, readableFormatFromTypeOrExtension(type = null, extension = null, fileName = "Issue.cbz"))
        assertEquals(FormatType.MOBI, readableFormatFromTypeOrExtension(type = "MOBI", extension = "mobi"))
        assertEquals(FormatType.AZW3, readableFormatFromTypeOrExtension(type = "AZW3", extension = "azw"))
        assertEquals(FormatType.AZW, readableFormatFromTypeOrExtension(type = null, extension = "azw"))
        assertEquals(FormatType.MOBI, readableFormatFromTypeOrExtension(type = null, extension = null, fileName = "old.prc"))
        assertEquals(FormatType.FB2, readableFormatFromTypeOrExtension(type = "FB2", extension = null))
        assertEquals(FormatType.DJVU, readableFormatFromTypeOrExtension(type = null, extension = "djv"))
        assertNull(readableFormatFromTypeOrExtension(type = "AUDIOBOOK", extension = "m4b", fileName = "book.m4b"))
    }

    @Test
    fun `a zipped FB2 is an FB2 and not a comic`() {
        assertEquals(FormatType.FB2, "War and Peace.fb2.zip".readableFormatOfFileName())
        assertEquals(FormatType.CBZ, "Issue 1.zip".readableFormatOfFileName())
        assertEquals(FormatType.FB2, readableFormatFromTypeOrExtension(type = null, extension = null, fileName = "a.FB2.ZIP"))
    }
}
