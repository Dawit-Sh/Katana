package com.example.unifiedreader.core.ebookconvert.epub

import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes [book] as an EPUB 3 (with an EPUB 2 table of contents too). The same book gives the same
 * bytes: entries keep one fixed date, so a book converted again is the identical file.
 */
internal fun writeEpub(
    book: EpubBook,
    output: OutputStream,
) {
    val zip = ZipOutputStream(output)
    zip.putStored(MIMETYPE_ENTRY, EPUB_MIMETYPE.toByteArray(Charsets.US_ASCII))
    zip.putDeflated(CONTAINER_ENTRY, containerXml().toByteArray(Charsets.UTF_8))
    zip.putDeflated("$CONTENT_DIRECTORY/$PACKAGE_FILE", packageDocument(book).toByteArray(Charsets.UTF_8))
    zip.putDeflated("$CONTENT_DIRECTORY/$NAV_FILE", navDocument(book).toByteArray(Charsets.UTF_8))
    zip.putDeflated("$CONTENT_DIRECTORY/$NCX_FILE", ncxDocument(book).toByteArray(Charsets.UTF_8))
    book.chapters.forEach { chapter ->
        zip.putDeflated("$CONTENT_DIRECTORY/${chapter.fileName}", chapter.xhtml.toByteArray(Charsets.UTF_8))
    }
    book.resources.forEach { resource ->
        val path = "$CONTENT_DIRECTORY/${resource.fileName}"
        if (resource.mediaType.startsWith("image/") && resource.mediaType != "image/svg+xml") {
            zip.putStored(path, resource.bytes)
        } else {
            zip.putDeflated(path, resource.bytes)
        }
    }
    zip.finish()
    zip.flush()
}

private fun ZipOutputStream.putStored(
    name: String,
    bytes: ByteArray,
) {
    val entry = ZipEntry(name).apply {
        method = ZipEntry.STORED
        size = bytes.size.toLong()
        compressedSize = bytes.size.toLong()
        crc = CRC32().also { it.update(bytes) }.value
        time = FIXED_ENTRY_TIME
    }
    putNextEntry(entry)
    write(bytes)
    closeEntry()
}

private fun ZipOutputStream.putDeflated(
    name: String,
    bytes: ByteArray,
) {
    val entry = ZipEntry(name).apply {
        method = ZipEntry.DEFLATED
        time = FIXED_ENTRY_TIME
    }
    putNextEntry(entry)
    write(bytes)
    closeEntry()
}

private fun containerXml(): String =
    """
    <?xml version="1.0" encoding="UTF-8"?>
    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
      <rootfiles>
        <rootfile full-path="$CONTENT_DIRECTORY/$PACKAGE_FILE" media-type="application/oebps-package+xml"/>
      </rootfiles>
    </container>
    """.trimIndent() + "\n"

internal const val CONTENT_DIRECTORY = "OEBPS"
internal const val PACKAGE_FILE = "content.opf"
internal const val NAV_FILE = "nav.xhtml"
internal const val NCX_FILE = "toc.ncx"
private const val MIMETYPE_ENTRY = "mimetype"
private const val CONTAINER_ENTRY = "META-INF/container.xml"
private const val EPUB_MIMETYPE = "application/epub+zip"

// 2000-01-01 00:00 local time; any fixed time keeps the bytes the same from one conversion to the next.
private const val FIXED_ENTRY_TIME = 946_684_800_000L
