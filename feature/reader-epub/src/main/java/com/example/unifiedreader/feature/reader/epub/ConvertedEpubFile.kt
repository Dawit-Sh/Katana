package com.example.unifiedreader.feature.reader.epub

import com.example.unifiedreader.core.ebookconvert.ConvertibleBookKind
import com.example.unifiedreader.core.ebookconvert.EbookConversionException
import com.example.unifiedreader.core.ebookconvert.EbookConverter
import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.feature.reader.core.ReaderOpenFailure
import java.io.File
import java.util.zip.CRC32

/**
 * The EPUB made from [book] (a MOBI, AZW, AZW3 or FB2 file) at [outputFile]. It is made once and
 * reused while the book and the converter stay the same, which a key file next to it records: the
 * converter's version and the book's size and checksum. A file that is really an EPUB (a server
 * may file one under another type) is used as it is.
 */
internal fun convertedEpubFile(
    book: ByteArray,
    format: FormatType,
    outputFile: File,
): File {
    val key = "${EbookConverter.VERSION}:${book.size}:${book.crc32()}"
    val keyFile = File(outputFile.path + KEY_SUFFIX)
    if (outputFile.isFile && keyFile.isFile && keyFile.readText() == key) return outputFile
    keyFile.delete()
    val temporary = File(outputFile.path + PART_SUFFIX)
    try {
        val kind = EbookConverter.detectKind(book)
        if (kind == null && book.isEpub()) {
            temporary.writeBytes(book)
        } else {
            temporary.outputStream().buffered().use { output ->
                EbookConverter.convertToEpub(kind ?: format.convertibleKind(), book, output)
            }
        }
        outputFile.delete()
        if (!temporary.renameTo(outputFile)) temporary.copyTo(outputFile, overwrite = true)
    } finally {
        temporary.delete()
    }
    keyFile.writeText(key)
    return outputFile
}

/** What the reader says when a book couldn't be converted. */
internal fun EbookConversionException.toReaderOpenFailure(): ReaderOpenFailure = when (reason) {
    EbookConversionException.Reason.DrmProtected -> ReaderOpenFailure.DrmLocked
    EbookConversionException.Reason.Unsupported -> ReaderOpenFailure.KindleFormatUnsupported
    EbookConversionException.Reason.NotThisFormat,
    EbookConversionException.Reason.Damaged,
    -> ReaderOpenFailure.NotReadable
}

private fun FormatType.convertibleKind(): ConvertibleBookKind =
    if (this == FormatType.FB2) ConvertibleBookKind.FB2 else ConvertibleBookKind.MOBI

private fun ByteArray.crc32(): String = "%08x".format(CRC32().also { it.update(this) }.value)

// A ZIP whose first entry is the EPUB mimetype.
private fun ByteArray.isEpub(): Boolean =
    size > EPUB_MIMETYPE_OFFSET + EPUB_MIMETYPE.length &&
        String(this, EPUB_MIMETYPE_OFFSET, EPUB_MIMETYPE.length, Charsets.US_ASCII) == EPUB_MIMETYPE

private const val KEY_SUFFIX = ".key"
private const val PART_SUFFIX = ".part"
private const val EPUB_MIMETYPE = "mimetypeapplication/epub+zip"
private const val EPUB_MIMETYPE_OFFSET = 30
