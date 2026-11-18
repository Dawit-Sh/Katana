package com.example.unifiedreader.core.ebookconvert

import java.io.IOException

/** The book files Katana converts to EPUB: Mobipocket and Kindle (MOBI, AZW, AZW3), and FictionBook 2. */
enum class ConvertibleBookKind {
    MOBI,
    FB2,
}

/** What a book says about itself, read without converting it. */
data class EbookMetadata(
    val title: String? = null,
    val authors: List<String> = emptyList(),
    val description: String? = null,
    val language: String? = null,
    val publisher: String? = null,
    val series: String? = null,
    val seriesIndex: String? = null,
    val cover: EbookImage? = null,
    /** Whether its pages turn right to left (a Kindle book can say so). */
    val rightToLeft: Boolean = false,
)

/** An image from a book: its media type and bytes. */
class EbookImage(
    val mediaType: String,
    val bytes: ByteArray,
)

/** Why a book couldn't be converted. */
class EbookConversionException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {
    enum class Reason {
        /** The file isn't a book of the kind asked for (or of any kind Katana converts). */
        NotThisFormat,

        /** A Kindle book locked to its owner's Kindle. */
        DrmProtected,

        /** A Kindle format Katana doesn't read (Topaz, KFX). */
        Unsupported,

        /** The file is cut short or broken. */
        Damaged,
    }
}
