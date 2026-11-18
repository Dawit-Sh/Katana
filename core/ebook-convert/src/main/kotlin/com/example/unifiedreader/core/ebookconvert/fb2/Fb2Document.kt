package com.example.unifiedreader.core.ebookconvert.fb2

import com.example.unifiedreader.core.ebookconvert.EbookConversionException
import com.example.unifiedreader.core.ebookconvert.EbookImage
import com.example.unifiedreader.core.ebookconvert.EbookMetadata
import com.example.unifiedreader.core.ebookconvert.sniffImageMediaType
import java.nio.charset.Charset
import java.util.Base64
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/**
 * A FictionBook 2 file, parsed leniently: FB2 files in the wild are often not quite well-formed XML
 * (HTML entities, stray tags), and readers open them anyway.
 */
internal class Fb2Document private constructor(
    val root: Element,
) {
    private val description: Element? = root.firstChildNamed("description")
    private val titleInfo: Element? = description?.firstChildNamed("title-info")

    /** The bodies with the text; notes and comments bodies are [noteBodies]. */
    val textBodies: List<Element> = root.childrenNamed("body").filterNot(Element::isNotesBody)
    val noteBodies: List<Element> = root.childrenNamed("body").filter(Element::isNotesBody)

    /** Images by id, as `<binary>` elements hold them. */
    val binaries: Map<String, EbookImage> by lazy {
        root.childrenNamed("binary").mapNotNull { binary ->
            val id = binary.attr("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val bytes = runCatching { Base64.getMimeDecoder().decode(binary.wholeText().trim()) }.getOrNull()
                ?: return@mapNotNull null
            val mediaType = sniffImageMediaType(bytes)
                ?: binary.attr("content-type").takeIf { it.startsWith("image/") }
                ?: return@mapNotNull null
            id to EbookImage(mediaType = mediaType, bytes = bytes)
        }.toMap()
    }

    /** The id of the cover's binary, from the title info's cover page. */
    val coverBinaryId: String? = titleInfo?.firstChildNamed("coverpage")
        ?.childrenNamed("image")
        ?.firstOrNull()
        ?.linkTarget()

    val metadata: EbookMetadata by lazy {
        val sequence = titleInfo?.firstChildNamed("sequence")
        EbookMetadata(
            title = titleInfo?.firstChildNamed("book-title")?.text()?.trim()?.takeIf(String::isNotEmpty),
            authors = titleInfo?.childrenNamed("author").orEmpty().mapNotNull(Element::authorName).distinct(),
            description = titleInfo?.firstChildNamed("annotation")?.let(Element::plainParagraphs),
            language = titleInfo?.firstChildNamed("lang")?.text()?.trim()?.takeIf(String::isNotEmpty),
            publisher = description?.firstChildNamed("publish-info")?.firstChildNamed("publisher")?.text()?.trim()
                ?.takeIf(String::isNotEmpty),
            series = sequence?.attr("name")?.trim()?.takeIf(String::isNotEmpty),
            seriesIndex = sequence?.attr("number")?.trim()?.takeIf(String::isNotEmpty),
            cover = coverBinaryId?.let { binaries[it] },
        )
    }

    companion object {
        fun parse(bytes: ByteArray): Fb2Document {
            val xmlBytes = if (bytes.isZip()) bytes.firstFb2InZip() else bytes
            val document: Document = Jsoup.parse(decodeXml(xmlBytes), "", Parser.xmlParser())
            val root = document.children().firstOrNull { it.fb2Name() == "FictionBook" }
                ?: throw EbookConversionException(EbookConversionException.Reason.NotThisFormat, "Not a FictionBook file")
            return Fb2Document(root)
        }
    }
}

/** Whether [bytes] look like an FB2 file, zipped or not. */
internal fun looksLikeFb2(bytes: ByteArray): Boolean {
    if (bytes.isZip()) return bytes.zipEntryNames().any { it.endsWith(".fb2", ignoreCase = true) }
    val head = String(bytes, 0, minOf(bytes.size, SNIFF_LENGTH), Charsets.ISO_8859_1)
    return head.contains("<FictionBook")
}

private fun Element.isNotesBody(): Boolean = attr("name").lowercase() in NOTES_BODY_NAMES

private fun Element.authorName(): String? {
    val parts = listOf("first-name", "middle-name", "last-name")
        .mapNotNull { name -> firstChildNamed(name)?.text()?.trim()?.takeIf(String::isNotEmpty) }
    val fullName = parts.joinToString(" ").ifEmpty { firstChildNamed("nickname")?.text()?.trim().orEmpty() }
    return fullName.takeIf(String::isNotEmpty)
}

private fun Element.plainParagraphs(): String? {
    val paragraphs = getAllElements().filter { it.fb2Name() == "p" }.map { it.text().trim() }.filter(String::isNotEmpty)
    return (paragraphs.ifEmpty { listOf(text().trim()) }).joinToString("\n\n").takeIf(String::isNotBlank)
}

// The declared encoding, else UTF-8 (with or without a byte order mark). Many Russian FB2 files are windows-1251.
private fun decodeXml(bytes: ByteArray): String {
    val head = String(bytes, 0, minOf(bytes.size, SNIFF_LENGTH), Charsets.ISO_8859_1)
    val declared = XmlEncodingPattern.find(head)?.groupValues?.get(1)
    val charset = declared?.let { name -> runCatching { Charset.forName(name) }.getOrNull() } ?: Charsets.UTF_8
    return String(bytes, charset).removePrefix("﻿")
}

private val XmlEncodingPattern = Regex("""<\?xml[^>]*encoding\s*=\s*["']([A-Za-z0-9._-]+)["']""")
private val NOTES_BODY_NAMES = setOf("notes", "comments", "footnotes")
private const val SNIFF_LENGTH = 4096
