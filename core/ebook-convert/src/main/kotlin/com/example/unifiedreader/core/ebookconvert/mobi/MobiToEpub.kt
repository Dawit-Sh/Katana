package com.example.unifiedreader.core.ebookconvert.mobi

import com.example.unifiedreader.core.ebookconvert.EbookConversionException
import com.example.unifiedreader.core.ebookconvert.epub.EpubBook

/**
 * A MOBI, AZW or AZW3 book as an EPUB: from its KF8 part when it has one, which keeps the
 * publisher's layout, else from its MOBI 6 part. A KF8 part that won't convert falls back to MOBI 6.
 */
internal fun mobiToEpubBook(
    bytes: ByteArray,
    identifier: String,
): EpubBook {
    val book = MobiBook.open(bytes)
    val kf8 = book.kf8
    val mobi6 = book.mobi6
    if (kf8 == null) return mobi6ToEpubBook(book, requireNotNull(mobi6), identifier)
    return try {
        kf8ToEpubBook(book, kf8, identifier)
    } catch (error: EbookConversionException) {
        if (mobi6 == null || error.reason != EbookConversionException.Reason.Damaged) throw error
        mobi6ToEpubBook(book, mobi6, identifier)
    }
}
