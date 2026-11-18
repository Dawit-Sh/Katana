package com.example.unifiedreader.core.ebookconvert.epub

/** The package document (OPF): metadata, every file, and the reading order. */
internal fun packageDocument(book: EpubBook): String = buildString {
    val language = book.language()
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"book-id\"")
    append(" xml:lang=\"").append(language.escapeXml()).append("\">\n")
    appendMetadata(book, language)
    appendManifest(book)
    append("<spine toc=\"ncx\"")
    if (book.metadata.rightToLeft) append(" page-progression-direction=\"rtl\"")
    append(">\n")
    book.chapters.forEach { chapter ->
        append("<itemref idref=\"").append(chapter.fileName.manifestId()).append("\"/>\n")
    }
    append("</spine>\n</package>\n")
}

private fun StringBuilder.appendMetadata(
    book: EpubBook,
    language: String,
) {
    val metadata = book.metadata
    append("<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n")
    append("<dc:identifier id=\"book-id\">").append(book.identifier.escapeXml()).append("</dc:identifier>\n")
    append("<dc:title>").append((metadata.title?.takeIf(String::isNotBlank) ?: UNTITLED).escapeXml()).append("</dc:title>\n")
    metadata.authors.forEach { author ->
        append("<dc:creator>").append(author.escapeXml()).append("</dc:creator>\n")
    }
    append("<dc:language>").append(language.escapeXml()).append("</dc:language>\n")
    metadata.publisher?.let { append("<dc:publisher>").append(it.escapeXml()).append("</dc:publisher>\n") }
    metadata.description?.let { append("<dc:description>").append(it.escapeXml()).append("</dc:description>\n") }
    metadata.series?.let { series ->
        append("<meta property=\"belongs-to-collection\" id=\"series\">").append(series.escapeXml()).append("</meta>\n")
        append("<meta refines=\"#series\" property=\"collection-type\">series</meta>\n")
        metadata.seriesIndex?.let { index ->
            append("<meta refines=\"#series\" property=\"group-position\">").append(index.escapeXml()).append("</meta>\n")
        }
    }
    append("<meta property=\"dcterms:modified\">2000-01-01T00:00:00Z</meta>\n")
    book.coverImage?.let { append("<meta name=\"cover\" content=\"").append(it.manifestId()).append("\"/>\n") }
    append("</metadata>\n")
}

private fun StringBuilder.appendManifest(book: EpubBook) {
    append("<manifest>\n")
    append("<item id=\"nav\" href=\"$NAV_FILE\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n")
    append("<item id=\"ncx\" href=\"$NCX_FILE\" media-type=\"application/x-dtbncx+xml\"/>\n")
    book.chapters.forEach { chapter ->
        appendItem(
            fileName = chapter.fileName,
            mediaType = XHTML_MEDIA_TYPE,
            properties = "svg".takeIf { chapter.xhtml.contains("<svg") },
        )
    }
    book.resources.forEach { resource ->
        appendItem(
            fileName = resource.fileName,
            mediaType = resource.mediaType,
            properties = "cover-image".takeIf { resource.fileName == book.coverImage },
        )
    }
    append("</manifest>\n")
}

private fun StringBuilder.appendItem(
    fileName: String,
    mediaType: String,
    properties: String?,
) {
    append("<item id=\"").append(fileName.manifestId()).append("\" href=\"").append(fileName.escapeXml())
    append("\" media-type=\"").append(mediaType.escapeXml()).append('"')
    if (properties != null) append(" properties=\"").append(properties).append('"')
    append("/>\n")
}

/** The EPUB 3 navigation document. */
internal fun navDocument(book: EpubBook): String {
    val body = buildString {
        append("<nav epub:type=\"toc\" id=\"toc\">\n<h1>").append(CONTENTS_TITLE).append("</h1>\n")
        appendNavList(book.tableOfContentsOrChapters())
        append("</nav>")
    }
    return xhtmlDocument(
        title = book.metadata.title ?: CONTENTS_TITLE,
        language = book.language(),
        stylesheets = emptyList(),
        body = body,
    )
}

private fun StringBuilder.appendNavList(entries: List<EpubTocEntry>) {
    append("<ol>\n")
    entries.forEach { entry ->
        append("<li><a href=\"").append(entry.href.escapeXml()).append("\">").append(entry.title.escapeXml()).append("</a>")
        if (entry.children.isNotEmpty()) {
            append('\n')
            appendNavList(entry.children)
        }
        append("</li>\n")
    }
    append("</ol>\n")
}

/** The EPUB 2 table of contents, for reading systems that look for it. */
internal fun ncxDocument(book: EpubBook): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    append("<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">\n<head>\n")
    append("<meta name=\"dtb:uid\" content=\"").append(book.identifier.escapeXml()).append("\"/>\n")
    append("</head>\n<docTitle><text>").append((book.metadata.title ?: UNTITLED).escapeXml()).append("</text></docTitle>\n")
    append("<navMap>\n")
    var playOrder = 0
    fun appendPoints(entries: List<EpubTocEntry>) {
        entries.forEach { entry ->
            playOrder += 1
            append("<navPoint id=\"nav-").append(playOrder).append("\" playOrder=\"").append(playOrder).append("\">")
            append("<navLabel><text>").append(entry.title.escapeXml()).append("</text></navLabel>")
            append("<content src=\"").append(entry.href.escapeXml()).append("\"/>\n")
            appendPoints(entry.children)
            append("</navPoint>\n")
        }
    }
    appendPoints(book.tableOfContentsOrChapters())
    append("</navMap>\n</ncx>\n")
}

// A navigation document needs at least one entry: without a table of contents, each chapter.
private fun EpubBook.tableOfContentsOrChapters(): List<EpubTocEntry> = tableOfContents.ifEmpty {
    chapters.mapIndexed { index, chapter -> EpubTocEntry(title = "Part ${index + 1}", href = chapter.fileName) }
}

internal fun EpubBook.language(): String = metadata.language?.takeIf(String::isNotBlank) ?: DEFAULT_LANGUAGE

/** A manifest id for [this] file name: XML ids may not start with a digit or hold most punctuation. */
internal fun String.manifestId(): String = "id-" + map { char ->
    if (char.isLetterOrDigit() || char == '-' || char == '_') char else '_'
}.joinToString(separator = "")

internal const val XHTML_MEDIA_TYPE = "application/xhtml+xml"
internal const val DEFAULT_LANGUAGE = "en"
private const val UNTITLED = "Untitled"
private const val CONTENTS_TITLE = "Contents"
