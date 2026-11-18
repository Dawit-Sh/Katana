package com.example.unifiedreader.core.ebookconvert.fb2

import com.example.unifiedreader.core.ebookconvert.epub.DEFAULT_LANGUAGE
import com.example.unifiedreader.core.ebookconvert.epub.EpubBook
import com.example.unifiedreader.core.ebookconvert.epub.EpubChapter
import com.example.unifiedreader.core.ebookconvert.epub.EpubResource
import com.example.unifiedreader.core.ebookconvert.epub.EpubTocEntry
import com.example.unifiedreader.core.ebookconvert.epub.escapeXml
import com.example.unifiedreader.core.ebookconvert.epub.xhtmlDocument
import com.example.unifiedreader.core.ebookconvert.imageExtension
import java.util.IdentityHashMap
import org.jsoup.nodes.Element

/**
 * An FB2 book as an EPUB: a cover page, a chapter per top-level section (big sections split at
 * their subsections, runs of short ones such as poems kept together), and the notes as EPUB
 * footnotes in a file of their own, so their links open as notes.
 */
internal fun fb2ToEpubBook(
    bytes: ByteArray,
    identifier: String,
): EpubBook = Fb2EpubBuilder(Fb2Document.parse(bytes), identifier).build()

private class Fb2EpubBuilder(
    private val document: Fb2Document,
    private val identifier: String,
) {
    private val metadata = document.metadata
    private val language = metadata.language ?: DEFAULT_LANGUAGE
    private val imageFiles: Map<String, String> = document.binaries.entries.mapIndexed { index, (id, image) ->
        id to "image%04d.%s".format(index + 1, imageExtension(image.mediaType))
    }.toMap()
    private val generatedSectionIds = IdentityHashMap<Element, String>()
    private val renderer = Fb2Renderer(imageFileFor = imageFiles::get, sectionIdFor = ::sectionId)
    private val chapters = mutableListOf<PendingChapter>()
    private var mergedRun: PendingChapter? = null

    fun build(): EpubBook {
        val coverFile = document.coverBinaryId?.let(imageFiles::get)
        if (coverFile != null) {
            val body = "<div class=\"cover\"><img src=\"${coverFile.escapeXml()}\" alt=\"\"/></div>"
            chapters += PendingChapter(COVER_FILE, metadata.title.orEmpty(), body, emptyList())
        }
        val tableOfContents = document.textBodies.flatMap(::addBody)
        flushMergedRun()
        val notesEntry = addNotes()
        return EpubBook(
            identifier = identifier,
            metadata = metadata,
            chapters = finishedChapters(chapters, metadata.title.orEmpty(), language),
            resources = fb2Resources(document, imageFiles),
            tableOfContents = tableOfContents + listOfNotNull(notesEntry),
            coverImage = coverFile,
        )
    }

    private fun addBody(body: Element): List<EpubTocEntry> {
        val head = Fb2Output()
        renderer.bodyHead(body, head)
        if (head.toString().isNotBlank()) {
            flushMergedRun()
            addChapter(head, title = body.firstChildNamed("title")?.text())
        }
        return body.childrenNamed("section").mapNotNull { section -> addTopSection(section) }
    }

    private fun addTopSection(section: Element): EpubTocEntry? {
        val whole = Fb2Output()
        renderer.section(section, depth = 1, out = whole)
        if (whole.length < SHORT_SECTION_CHARS) {
            val run = mergedRun
            if (run != null && run.body.length + whole.length > MERGED_RUN_CHARS) flushMergedRun()
            val file = mergedRun?.fileName ?: nextChapterFileName()
            mergedRun = mergedRun?.plus(whole) ?: PendingChapter(file, section.titleText(), whole.toString(), whole.ids)
            return section.tocEntry(file, ::sectionId, children = nestedTocEntries(section, file, ::sectionId))
        }
        flushMergedRun()
        return addSection(section, depth = 1, whole = whole)
    }

    private fun addSection(
        section: Element,
        depth: Int,
        whole: Fb2Output,
    ): EpubTocEntry? {
        val subsections = section.childrenNamed("section")
        if (whole.length <= MAX_CHAPTER_CHARS || subsections.isEmpty()) {
            val file = addChapter(whole, section.titleText())
            return section.tocEntry(file, ::sectionId, children = nestedTocEntries(section, file, ::sectionId), isFileStart = true)
        }
        val head = Fb2Output()
        renderer.section(section, depth, head, withSubsections = false)
        val file = addChapter(head, section.titleText())
        val children = subsections.mapNotNull { subsection ->
            val subsectionWhole = Fb2Output()
            renderer.section(subsection, depth + 1, subsectionWhole)
            addSection(subsection, depth + 1, subsectionWhole)
        }
        return section.tocEntry(file, ::sectionId, children = children, isFileStart = true)
    }

    private fun addNotes(): EpubTocEntry? {
        if (document.noteBodies.isEmpty()) return null
        val out = Fb2Output()
        document.noteBodies.forEach { body ->
            renderer.bodyHead(body, out)
            body.childrenNamed("section").forEach { section -> renderer.note(section, out) }
        }
        val file = addChapter(out, title = NOTES_TITLE)
        return EpubTocEntry(title = NOTES_TITLE, href = file)
    }

    private fun addChapter(
        out: Fb2Output,
        title: String?,
    ): String {
        val file = nextChapterFileName()
        chapters += PendingChapter(file, title, out.toString(), out.ids.toList())
        return file
    }

    private fun flushMergedRun() {
        mergedRun?.let { chapters += it }
        mergedRun = null
    }

    private fun nextChapterFileName(): String = "chapter%04d.xhtml".format(chapters.size + (if (mergedRun != null) 2 else 1))

    private fun sectionId(section: Element): String = section.attr("id").takeIf(String::isNotBlank)
        ?: generatedSectionIds.getOrPut(section) { "katana-section-${generatedSectionIds.size + 1}" }
}

// The titled sections inside [section], all in [file]; an untitled one's titled sections take its place.
private fun nestedTocEntries(
    section: Element,
    file: String,
    sectionId: (Element) -> String,
): List<EpubTocEntry> = section.childrenNamed("section").flatMap { subsection ->
    val children = nestedTocEntries(subsection, file, sectionId)
    subsection.tocEntry(file, sectionId, children)?.let(::listOf) ?: children
}

private fun Element.tocEntry(
    file: String,
    sectionId: (Element) -> String,
    children: List<EpubTocEntry>,
    isFileStart: Boolean = false,
): EpubTocEntry? = titleText()?.let { title ->
    EpubTocEntry(title = title, href = if (isFileStart) file else "$file#${sectionId(this)}", children = children)
}

// The chapters as XHTML documents, each placeholder link pointing at the file that holds its anchor.
private fun finishedChapters(
    chapters: List<PendingChapter>,
    bookTitle: String,
    language: String,
): List<EpubChapter> {
    val fileOfId = HashMap<String, String>()
    chapters.forEach { chapter -> chapter.ids.forEach { id -> fileOfId.putIfAbsent(id, chapter.fileName) } }
    return chapters.map { chapter ->
        val body = InternalLinkPattern.replace(chapter.body) { match ->
            val id = match.groupValues[1]
            val file = fileOfId[unescapeAttribute(id)]
            val href = if (file == null || file == chapter.fileName) "#$id" else "$file#$id"
            "href=\"$href\""
        }
        EpubChapter(
            fileName = chapter.fileName,
            xhtml = xhtmlDocument(
                title = chapter.title ?: bookTitle,
                language = language,
                stylesheets = listOf(STYLESHEET_FILE),
                body = body,
                bodyClass = if (chapter.fileName == COVER_FILE) "cover" else null,
            ),
        )
    }
}

private fun fb2Resources(
    document: Fb2Document,
    imageFiles: Map<String, String>,
): List<EpubResource> =
    listOf(EpubResource(STYLESHEET_FILE, "text/css", Fb2Stylesheet.toByteArray(Charsets.UTF_8))) +
        document.binaries.mapNotNull { (id, image) ->
            imageFiles[id]?.let { file -> EpubResource(file, image.mediaType, image.bytes) }
        }

private data class PendingChapter(
    val fileName: String,
    val title: String?,
    val body: String,
    val ids: List<String>,
) {
    fun plus(more: Fb2Output): PendingChapter = copy(body = body + more.toString(), ids = ids + more.ids)
}

private fun Element.titleText(): String? = firstChildNamed("title")
    ?.children()
    ?.joinToString(" ") { it.text().trim() }
    ?.trim()
    ?.takeIf(String::isNotEmpty)

private fun unescapeAttribute(value: String): String = value
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&amp;", "&")

private val InternalLinkPattern = Regex("""href="$INTERNAL_LINK_SCHEME([^"]*)"""")

private const val COVER_FILE = "cover.xhtml"
private const val STYLESHEET_FILE = "style.css"
private const val NOTES_TITLE = "Notes"

// A chapter bigger than this splits at its subsections: the Fire's WebView lays out big files slowly.
private const val MAX_CHAPTER_CHARS = 150_000

// Top-level sections shorter than this (poems, short stories' parts) share a file, up to the run size.
private const val SHORT_SECTION_CHARS = 3_000
private const val MERGED_RUN_CHARS = 40_000

private val Fb2Stylesheet = """
    h1, h2, h3, h4, h5, h6 { text-align: center; text-indent: 0; margin: 1em 0 0.8em; page-break-after: avoid; }
    h1 { font-size: 1.6em; }
    h2 { font-size: 1.4em; }
    h3 { font-size: 1.2em; }
    h4, h5, h6 { font-size: 1.1em; }
    p { margin: 0; text-indent: 1.5em; }
    p.empty-line, p.subtitle, p.text-author, p.date, p.verse, p.note-title, p.image-title { text-indent: 0; }
    p.subtitle { text-align: center; font-weight: bold; margin: 1em 0 0.5em; }
    p.text-author { text-align: right; font-style: italic; }
    p.date { text-align: right; font-size: 0.9em; }
    blockquote { margin: 1em 0 1em 2em; }
    blockquote.epigraph { margin: 1em 0 1em 30%; font-style: italic; }
    div.poem { margin: 1em 0 1em 2em; }
    div.stanza { margin: 0.8em 0; }
    p.verse { padding-left: 1em; text-indent: -1em; }
    p.poem-title, p.stanza-title, p.epigraph-title, p.cite-title { font-weight: bold; text-indent: 0; }
    div.image { text-align: center; margin: 1em 0; text-indent: 0; }
    div.image img { max-width: 100%; }
    p.image-title { text-align: center; font-size: 0.9em; }
    aside.note { display: block; margin: 0 0 1em; }
    p.note-title { font-weight: bold; }
    div.cover { text-align: center; margin: 0; }
    div.cover img { max-width: 100%; max-height: 95vh; }
    table { border-collapse: collapse; margin: 1em auto; }
    td, th { border: 1px solid #888; padding: 0.2em 0.4em; text-indent: 0; }
""".trimIndent() + "\n"
