package com.example.unifiedreader.core.ebookconvert.epub

import com.example.unifiedreader.core.ebookconvert.EbookMetadata

/**
 * A book ready to be written as an EPUB 3. Every file sits next to the package document, so
 * chapters, images and stylesheets refer to each other by bare file name.
 */
internal class EpubBook(
    val identifier: String,
    val metadata: EbookMetadata,
    /** The chapters in reading order. */
    val chapters: List<EpubChapter>,
    val resources: List<EpubResource>,
    val tableOfContents: List<EpubTocEntry>,
    /** The file name of the cover image among [resources], if the book has one. */
    val coverImage: String? = null,
)

/** One XHTML file of the book: [xhtml] is the whole document. */
internal class EpubChapter(
    val fileName: String,
    val xhtml: String,
)

/** An image, stylesheet or font. */
internal class EpubResource(
    val fileName: String,
    val mediaType: String,
    val bytes: ByteArray,
)

/** A table of contents entry: [href] is a chapter file name, with an anchor or without. */
internal data class EpubTocEntry(
    val title: String,
    val href: String,
    val children: List<EpubTocEntry> = emptyList(),
)
