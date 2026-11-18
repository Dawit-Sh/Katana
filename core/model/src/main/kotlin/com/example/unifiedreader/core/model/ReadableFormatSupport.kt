package com.example.unifiedreader.core.model

/** The formats Katana turns into an EPUB on the device and reads in the EPUB reader. */
val ConvertedToEpubFormats: Set<FormatType> = setOf(
    FormatType.MOBI,
    FormatType.AZW,
    FormatType.AZW3,
    FormatType.FB2,
)

/** The formats the EPUB reader opens: EPUB itself and the ones converted to it. */
val EpubReaderFormats: Set<FormatType> = setOf(FormatType.EPUB) + ConvertedToEpubFormats

/** The formats read page by page as pictures of pages: PDF and DjVu. */
val PageImageBookFormats: Set<FormatType> = setOf(
    FormatType.PDF,
    FormatType.DJVU,
)

val BookReaderFormats: Set<FormatType> = EpubReaderFormats + PageImageBookFormats

val ComicReaderFormats: Set<FormatType> = setOf(
    FormatType.CBZ,
    FormatType.CBR,
)

val AppReadableFormats: Set<FormatType> = BookReaderFormats + ComicReaderFormats

fun FormatType.isBookReaderFormat(): Boolean = this in BookReaderFormats

fun FormatType.isComicReaderFormat(): Boolean = this in ComicReaderFormats

/** Whether the EPUB reader opens it, so its places are EPUB locators (CFI) rather than pages. */
fun FormatType.opensInEpubReader(): Boolean = this in EpubReaderFormats

/** Whether Katana converts it to an EPUB before reading. */
fun FormatType.isConvertedToEpub(): Boolean = this in ConvertedToEpubFormats

fun String?.toReadableFormatTypeOrNull(): FormatType? {
    val normalized = this?.trim()
        ?.removePrefix(".")
        ?.uppercase()
        ?: return null
    return when (normalized) {
        "EPUB" -> FormatType.EPUB
        "PDF" -> FormatType.PDF
        "CBZ", "ZIP" -> FormatType.CBZ
        "CBR", "RAR" -> FormatType.CBR
        "MOBI", "PRC" -> FormatType.MOBI
        "AZW" -> FormatType.AZW
        "AZW3" -> FormatType.AZW3
        "FB2", "FB2.ZIP" -> FormatType.FB2
        "DJVU", "DJV" -> FormatType.DJVU
        else -> null
    }
}

fun readableFormatFromTypeOrExtension(
    type: String?,
    extension: String?,
    fileName: String? = null,
): FormatType? {
    return type.toReadableFormatTypeOrNull()
        ?: extension.toReadableFormatTypeOrNull()
        ?: fileName?.readableFormatOfFileName()
}

/** The format a file name says, reading `.fb2.zip` as FB2 rather than a ZIP of comic pages. */
fun String.readableFormatOfFileName(): FormatType? =
    if (endsWith(".fb2.zip", ignoreCase = true)) {
        FormatType.FB2
    } else {
        substringAfterLast('.', missingDelimiterValue = "").toReadableFormatTypeOrNull()
    }
