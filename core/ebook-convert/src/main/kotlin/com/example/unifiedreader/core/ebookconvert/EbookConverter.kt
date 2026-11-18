package com.example.unifiedreader.core.ebookconvert

import com.example.unifiedreader.core.ebookconvert.epub.EpubBook
import com.example.unifiedreader.core.ebookconvert.epub.writeEpub
import com.example.unifiedreader.core.ebookconvert.fb2.Fb2Document
import com.example.unifiedreader.core.ebookconvert.fb2.fb2ToEpubBook
import com.example.unifiedreader.core.ebookconvert.fb2.looksLikeFb2
import com.example.unifiedreader.core.ebookconvert.mobi.MobiBook
import com.example.unifiedreader.core.ebookconvert.mobi.looksLikeMobi
import com.example.unifiedreader.core.ebookconvert.mobi.mobiToEpubBook
import java.io.OutputStream
import java.util.zip.CRC32

/**
 * Turns MOBI, AZW, AZW3 and FB2 books into EPUBs for the EPUB reader, the way Librera turns them
 * into something its engine reads. The same file always gives the same EPUB, so places and
 * highlights saved in it stay valid; [VERSION] changes when the EPUB it makes changes.
 */
object EbookConverter {
    /** Bumped whenever the EPUBs made change, so copies made by an older converter are made again. */
    const val VERSION: Int = 1

    /** Which kind [book] is, from its first bytes, or null when it is neither. */
    fun detectKind(book: ByteArray): ConvertibleBookKind? = when {
        looksLikeMobi(book) -> ConvertibleBookKind.MOBI
        looksLikeFb2(book) -> ConvertibleBookKind.FB2
        else -> null
    }

    /**
     * Writes [book], a whole MOBI/AZW/AZW3 or FB2 file, to [output] as an EPUB.
     *
     * @throws EbookConversionException when the file can't be converted, saying why.
     */
    fun convertToEpub(
        kind: ConvertibleBookKind,
        book: ByteArray,
        output: OutputStream,
    ) {
        writeEpub(epubBook(kind, book), output)
    }

    /** The title, authors, description and cover [book] holds, without converting it. */
    fun readMetadata(
        kind: ConvertibleBookKind,
        book: ByteArray,
    ): EbookMetadata = guarded {
        when (kind) {
            ConvertibleBookKind.MOBI -> MobiBook.open(book).metadata()
            ConvertibleBookKind.FB2 -> Fb2Document.parse(book).metadata
        }
    }

    internal fun epubBook(
        kind: ConvertibleBookKind,
        book: ByteArray,
    ): EpubBook = guarded {
        val identifier = "urn:katana:${kind.name.lowercase()}:${book.crc32Hex()}-${book.size}"
        when (kind) {
            ConvertibleBookKind.MOBI -> mobiToEpubBook(book, identifier)
            ConvertibleBookKind.FB2 -> fb2ToEpubBook(book, identifier)
        }
    }
}

// What a broken file makes the parsers throw (reading past its end, a value out of range) becomes
// Damaged; anything else, out of memory among them, stays what it is.
private inline fun <T> guarded(block: () -> T): T = runCatching(block).getOrElse { error ->
    throw when (error) {
        is EbookConversionException -> error
        is IndexOutOfBoundsException,
        is IllegalArgumentException,
        is IllegalStateException,
        -> EbookConversionException(EbookConversionException.Reason.Damaged, "The file is damaged or cut short", error)
        else -> error
    }
}

private fun ByteArray.crc32Hex(): String = "%08x".format(CRC32().also { it.update(this) }.value)
