package com.example.unifiedreader.core.ebookconvert.mobi

import com.example.unifiedreader.core.ebookconvert.epub.EpubBook
import com.example.unifiedreader.core.ebookconvert.epub.EpubChapter
import com.example.unifiedreader.core.ebookconvert.epub.EpubResource
import com.example.unifiedreader.core.ebookconvert.epub.EpubTocEntry
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/**
 * A KF8 (AZW3) book as an EPUB: its XHTML files rebuilt, `kindle:` links turned back into file
 * links, and its stylesheets, images, fonts and table of contents written out.
 */
internal fun kf8ToEpubBook(
    book: MobiBook,
    header: MobiHeader,
    identifier: String,
): EpubBook = Kf8EpubBuilder(book, header).build(identifier)

private class Kf8EpubBuilder(
    private val book: MobiBook,
    private val header: MobiHeader,
) {
    private val text = Kf8Text(book, header)
    private val resources = MobiResources(book)
    private val flowFiles = HashMap<Int, EpubResource?>()
    private val positionLinks = HashMap<String, String>()

    fun build(identifier: String): EpubBook {
        resolvePositionLinks()
        val tableOfContents = tableOfContents()
        val chapters = text.parts.map { part -> EpubChapter(part.fileName, convertPart(part)) }
        val cover = book.coverResourceNumber()?.let(resources::fileFor)
        return EpubBook(
            identifier = identifier,
            metadata = book.metadata().copy(cover = null),
            chapters = chapters,
            resources = flowFiles.values.filterNotNull() + resources.resources,
            tableOfContents = tableOfContents,
            coverImage = cover,
        )
    }

    // Every kindle:pos link, resolved against the untouched files (positions count their bytes).
    private fun resolvePositionLinks() {
        text.parts.forEach { part ->
            PosFidLink.findAll(String(part.bytes, Charsets.ISO_8859_1)).forEach { match ->
                positionLinks.getOrPut(match.value) {
                    val anchor = text.anchorForPosFid(
                        fid = match.groupValues[1].fromKindleBase32().toInt(),
                        offset = match.groupValues[2].fromKindleBase32().toInt(),
                    )
                    anchor?.href() ?: match.value
                }
            }
        }
    }

    private fun tableOfContents(): List<EpubTocEntry> {
        val index = MobiIndex.read(book.database, header.ncxIndex)
        if (index.entries.isEmpty()) return emptyList()
        val entries = index.entries.map { entry ->
            val href = entry.value(NCX_TAG_POS_FID, 0)?.let { fid ->
                text.anchorForPosFid(fid.toInt(), entry.value(NCX_TAG_POS_FID, 1)?.toInt() ?: 0)?.href()
            } ?: entry.value(NCX_TAG_POSITION)?.let { text.anchorForPosition(it.toInt())?.href() }
            NcxEntry(
                title = index.label(entry.value(NCX_TAG_LABEL), header.charset),
                href = href,
                parent = entry.value(NCX_TAG_PARENT)?.toInt(),
            )
        }
        return ncxTree(entries)
    }

    private fun convertPart(part: Kf8Part): String {
        val document = Jsoup.parse(String(part.bytes, Charsets.UTF_8), "", Parser.xmlParser())
        document.getAllElements().forEach(::rewriteElement)
        return document.cleanForXhtml().toXhtmlString()
    }

    private fun rewriteElement(element: Element) {
        LinkAttributes.forEach { name ->
            val value = element.attr(name)
            if (value.contains("kindle:")) element.attr(name, rewriteKindleUrls(value))
        }
        if (element.hasAttr("style")) element.attr("style", rewriteKindleUrls(element.attr("style")))
        if (element.tagName().equals("style", ignoreCase = true)) {
            element.textNodes().forEach { node -> node.text(rewriteKindleUrls(node.wholeText)) }
            element.dataNodes().forEach { node -> node.setWholeData(rewriteKindleUrls(node.wholeData)) }
        }
        val aid = element.attributes().asList().firstOrNull { it.key.equals("aid", ignoreCase = true) }?.value
        if (aid != null) {
            element.attributes().asList().filter { it.key.equals("aid", ignoreCase = true) }.forEach { element.removeAttr(it.key) }
            if (aid in text.linkedAids && !element.hasAttr("id")) element.attr("id", "aid-$aid")
        }
    }

    private fun rewriteKindleUrls(value: String): String {
        var result = PosFidLink.replace(value) { match -> positionLinks[match.value] ?: match.value }
        result = EmbedLink.replace(result) { match ->
            resources.fileFor(match.groupValues[1].fromKindleBase32().toInt()) ?: match.value
        }
        return FlowLink.replace(result) { match -> flowFile(match.groupValues[1].fromKindleBase32().toInt()) ?: match.value }
    }

    // A flow (stylesheet or SVG) as a file of its own, its own kindle: links rewritten too.
    private fun flowFile(number: Int): String? {
        if (number !in flowFiles) {
            flowFiles[number] = null
            val bytes = text.flows.getOrNull(number)
            flowFiles[number] = bytes?.let {
                val content = String(it, Charsets.UTF_8)
                val isSvg = content.trimStart().let { start -> start.startsWith("<svg") || start.startsWith("<?xml") }
                EpubResource(
                    fileName = "flow%04d.%s".format(number, if (isSvg) "svg" else "css"),
                    mediaType = if (isSvg) "image/svg+xml" else "text/css",
                    bytes = rewriteKindleUrls(content).toByteArray(Charsets.UTF_8),
                )
            }
        }
        return flowFiles[number]?.fileName
    }

    private fun Kf8Anchor.href(): String = if (id.isNullOrEmpty()) fileName else "$fileName#$id"

    private companion object {
        val PosFidLink = Regex("""kindle:pos:fid:([0-9A-Va-v]{4}):off:([0-9A-Va-v]{10})""")
        val EmbedLink = Regex("""kindle:embed:([0-9A-Va-v]{4})(?:\?mime=[^)'"\s]*)?""")
        val FlowLink = Regex("""kindle:flow:([0-9A-Va-v]{4})(?:\?mime=[^)'"\s]*)?""")
        val LinkAttributes = listOf("href", "src", "xlink:href", "poster", "data")
        const val NCX_TAG_POSITION = 1
        const val NCX_TAG_LABEL = 3
        const val NCX_TAG_POS_FID = 6
        const val NCX_TAG_PARENT = 21
    }
}

/** A table of contents entry from a MOBI NCX index, before the tree is built. */
internal class NcxEntry(
    val title: String?,
    val href: String?,
    val parent: Int?,
)

/** The NCX entries as a tree: each entry under its parent, in the index's order. */
internal fun ncxTree(entries: List<NcxEntry>): List<EpubTocEntry> {
    val children = HashMap<Int, MutableList<Int>>()
    val roots = mutableListOf<Int>()
    entries.forEachIndexed { index, entry ->
        val parent = entry.parent?.takeIf { it in entries.indices && it != index }
        if (parent == null) roots += index else children.getOrPut(parent) { mutableListOf() } += index
    }
    fun build(index: Int, depth: Int): EpubTocEntry? {
        val entry = entries[index]
        val href = entry.href ?: return null
        val nested = if (depth < MAX_TOC_DEPTH) children[index].orEmpty().mapNotNull { build(it, depth + 1) } else emptyList()
        return EpubTocEntry(title = entry.title ?: "Untitled", href = href, children = nested)
    }
    return roots.mapNotNull { build(it, depth = 0) }
}

private const val MAX_TOC_DEPTH = 6
