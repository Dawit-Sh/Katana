package com.example.unifiedreader.core.ebookconvert.mobi

import com.example.unifiedreader.core.ebookconvert.epub.DEFAULT_LANGUAGE
import com.example.unifiedreader.core.ebookconvert.epub.EpubBook
import com.example.unifiedreader.core.ebookconvert.epub.EpubChapter
import com.example.unifiedreader.core.ebookconvert.epub.EpubResource
import com.example.unifiedreader.core.ebookconvert.epub.EpubTocEntry
import com.example.unifiedreader.core.ebookconvert.epub.xhtmlDocument
import java.util.TreeSet
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node

/**
 * An old-style MOBI book (MOBI 6, PalmDOC HTML) as an EPUB. Its one long HTML text splits at its
 * page breaks into chapters; `filepos` links and table of contents entries, which point at byte
 * positions, get anchors there; `recindex` images become image files.
 */
internal fun mobi6ToEpubBook(
    book: MobiBook,
    header: MobiHeader,
    identifier: String,
): EpubBook = Mobi6EpubBuilder(book, header).build(identifier)

private class Mobi6EpubBuilder(
    private val book: MobiBook,
    private val header: MobiHeader,
) {
    private val resources = MobiResources(book)
    private val ncx = MobiIndex.read(book.database, header.ncxIndex)

    fun build(identifier: String): EpubBook {
        val metadata = book.metadata()
        val html = anchoredHtml(book.text(header))
        val files = packFiles(html.split(PageBreak).map(::parseChunk))
        val fileOfId = HashMap<String, String>()
        files.forEach { file -> file.ids().forEach { id -> fileOfId.putIfAbsent(id, file.name) } }
        val chapters = files.map { file ->
            file.rewriteLinks(fileOfId)
            EpubChapter(
                fileName = file.name,
                xhtml = xhtmlDocument(
                    title = file.heading() ?: metadata.title.orEmpty(),
                    language = metadata.language ?: DEFAULT_LANGUAGE,
                    stylesheets = listOf(STYLESHEET_FILE),
                    body = file.xhtmlBody(),
                ),
            )
        }
        val cover = book.coverResourceNumber()?.let(resources::fileFor)
        return EpubBook(
            identifier = identifier,
            metadata = metadata.copy(cover = null),
            chapters = chapters,
            resources = listOf(EpubResource(STYLESHEET_FILE, "text/css", Mobi6Stylesheet.toByteArray())) + resources.resources,
            tableOfContents = tableOfContents(fileOfId, files),
            coverImage = cover,
        )
    }

    // The text with an anchor at every position a link or the table of contents points to.
    private fun anchoredHtml(raw: ByteArray): String {
        val latin = String(raw, Charsets.ISO_8859_1)
        val targets = TreeSet<Int>()
        FileposAttribute.findAll(latin).forEach { match -> match.groupValues[1].toIntOrNull()?.let(targets::add) }
        ncx.entries.forEach { entry -> entry.value(NCX_TAG_POSITION)?.toInt()?.let(targets::add) }
        val anchored = StringBuilder(latin.length + targets.size * ANCHOR_LENGTH_ESTIMATE)
        var copied = 0
        targets.filter { it in 0..latin.length }.forEach { target ->
            val insertAt = outsideTag(latin, target).coerceAtLeast(copied)
            anchored.append(latin, copied, insertAt).append("<a id=\"filepos").append(target).append("\"></a>")
            copied = insertAt
        }
        anchored.append(latin, copied, latin.length)
        val linked = FileposAttribute.replace(anchored) { match -> "href=\"#filepos${match.groupValues[1].toIntOrNull() ?: 0}\"" }
        val withImages = RecindexAttribute.replace(linked) { match -> "src=\"$RECINDEX_SCHEME${match.groupValues[1]}\"" }
        return String(withImages.toByteArray(Charsets.ISO_8859_1), header.charset)
    }

    // A position inside a tag moves to the tag's start.
    private fun outsideTag(
        text: String,
        position: Int,
    ): Int {
        val lastOpen = text.lastIndexOf('<', position - 1)
        val lastClose = text.lastIndexOf('>', position - 1)
        return if (lastOpen > lastClose) lastOpen else position
    }

    private fun parseChunk(html: String): List<Node> {
        val document: Document = Jsoup.parse(html)
        document.cleanForXhtml()
        document.select("img").forEach { image -> resolveImage(image) }
        document.select("p[width], p[height], div[width], div[height], blockquote[width], blockquote[height]")
            .forEach(::spacingToStyle)
        return document.body().childNodes().toList()
    }

    // MOBI 6 writes a paragraph's first-line indent as `width` and the space above it as `height`.
    private fun spacingToStyle(element: Element) {
        val styles = listOfNotNull(
            element.attr("width").takeIf(CssLength::matches)?.let { "text-indent: $it" },
            element.attr("height").takeIf(CssLength::matches)?.let { "margin-top: $it" },
        )
        element.removeAttr("width")
        element.removeAttr("height")
        if (styles.isEmpty()) return
        val existing = element.attr("style").trim().removeSuffix(";")
        element.attr("style", (listOf(existing).filter(String::isNotEmpty) + styles).joinToString("; "))
    }

    private fun resolveImage(image: Element) {
        val source = image.attr("src")
        if (!source.startsWith(RECINDEX_SCHEME)) return
        val file = source.removePrefix(RECINDEX_SCHEME).toIntOrNull()?.let(resources::fileFor)
        if (file == null) image.remove() else image.attr("src", file)
    }

    // One file per page-break chunk; near-empty chunks join the next, and huge ones split between blocks.
    private fun packFiles(chunks: List<List<Node>>): List<Mobi6File> {
        val files = mutableListOf<Mobi6File>()
        var pending = mutableListOf<Node>()
        var pendingChars = 0
        // Nodes with nothing to show (anchors) wait for the next file, so their anchors still land somewhere.
        fun flush() {
            if (pending.none(Node::hasContent)) return
            files += Mobi6File("part%04d.xhtml".format(files.size), pending)
            pending = mutableListOf()
            pendingChars = 0
        }
        chunks.forEach { nodes ->
            nodes.forEach { node ->
                pending += node
                pendingChars += node.outerHtml().length
                if (pendingChars > MAX_FILE_CHARS) flush()
            }
            if (pendingChars >= MIN_FILE_CHARS) flush()
        }
        flush()
        if (pending.isNotEmpty() || files.isEmpty()) files += Mobi6File("part%04d.xhtml".format(files.size), pending)
        return files
    }

    private fun tableOfContents(
        fileOfId: Map<String, String>,
        files: List<Mobi6File>,
    ): List<EpubTocEntry> {
        if (ncx.entries.isNotEmpty()) {
            val entries = ncx.entries.map { entry ->
                val id = entry.value(NCX_TAG_POSITION)?.let { "filepos$it" }
                NcxEntry(
                    title = ncx.label(entry.value(NCX_TAG_LABEL), header.charset),
                    href = id?.let { fileOfId[it]?.let { file -> "$file#$it" } },
                    parent = entry.value(NCX_TAG_PARENT)?.toInt(),
                )
            }
            ncxTree(entries).takeIf(List<EpubTocEntry>::isNotEmpty)?.let { return it }
        }
        return files.mapNotNull { file -> file.heading()?.let { EpubTocEntry(title = it, href = file.name) } }
    }

    private companion object {
        val FileposAttribute = Regex("""\bfilepos\s*=\s*["']?0*(\d+)["']?""", RegexOption.IGNORE_CASE)
        val RecindexAttribute = Regex("""\b(?:hi|lo)?recindex\s*=\s*["']?0*(\d+)["']?""", RegexOption.IGNORE_CASE)
        val CssLength = Regex("""-?\d+(?:\.\d+)?(?:em|ex|pt|px|%)?""")
        val PageBreak = Regex("""<mbp:pagebreak\s*/?>(?:\s*</mbp:pagebreak>)?""", RegexOption.IGNORE_CASE)
        const val RECINDEX_SCHEME = "katana-recindex:"
        const val NCX_TAG_POSITION = 1
        const val NCX_TAG_LABEL = 3
        const val NCX_TAG_PARENT = 21
        const val ANCHOR_LENGTH_ESTIMATE = 32
        const val STYLESHEET_FILE = "mobi.css"

        // Smaller chunks join the next file (a chunk holding only an anchor); bigger files split.
        const val MIN_FILE_CHARS = 600
        const val MAX_FILE_CHARS = 150_000
    }
}

/** An XHTML file being made from MOBI 6 HTML: its name and its body's nodes. */
private class Mobi6File(
    val name: String,
    private val nodes: List<Node>,
) {
    fun ids(): List<String> = nodes.flatMap { node ->
        (node as? Element)?.let { element -> element.select("[id]").map { it.id() } }.orEmpty()
    }

    fun heading(): String? = nodes.asSequence()
        .mapNotNull { node -> (node as? Element)?.selectFirst("h1, h2, h3") ?: (node as? Element)?.takeIf { it.tagName() in HEADINGS } }
        .map { it.text().trim() }
        .firstOrNull(String::isNotEmpty)

    fun rewriteLinks(fileOfId: Map<String, String>) {
        nodes.filterIsInstance<Element>().forEach { element ->
            element.select("a[href^=#]").forEach { link ->
                val id = link.attr("href").removePrefix("#")
                val file = fileOfId[id]
                if (file != null && file != name) link.attr("href", "$file#$id")
            }
        }
    }

    fun xhtmlBody(): String = nodes.joinToString(separator = "") { it.outerHtml() }

    private companion object {
        val HEADINGS = setOf("h1", "h2", "h3")
    }
}

private fun Node.hasContent(): Boolean = when (this) {
    is Element -> text().isNotBlank() || select("img, svg, hr, table").isNotEmpty()
    is org.jsoup.nodes.TextNode -> !isBlank
    else -> false
}

private val Mobi6Stylesheet = """
    p { margin: 0; text-indent: 1.5em; }
    img { max-width: 100%; }
    blockquote { margin: 1em 2em; }
    h1, h2, h3, h4 { text-align: center; text-indent: 0; }
""".trimIndent() + "\n"
