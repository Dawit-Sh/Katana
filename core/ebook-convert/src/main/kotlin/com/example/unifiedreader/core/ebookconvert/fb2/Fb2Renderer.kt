package com.example.unifiedreader.core.ebookconvert.fb2

import com.example.unifiedreader.core.ebookconvert.epub.escapeXml
import org.jsoup.nodes.Element

/**
 * Writes FB2 elements as XHTML. Links inside the book come out as [internalLinkHref] placeholders,
 * replaced once every chapter's file is known; [Fb2Output.ids] collects the anchors written, so
 * the chapter that holds each one is known.
 */
internal class Fb2Renderer(
    private val imageFileFor: (binaryId: String) -> String?,
    private val sectionIdFor: (section: Element) -> String,
) {
    private val inline = Fb2InlineWriter(imageFileFor)

    /** Writes [section] with its title at heading level [depth] + 1, and its subsections unless [withSubsections] is false. */
    fun section(
        section: Element,
        depth: Int,
        out: Fb2Output,
        withSubsections: Boolean = true,
    ) {
        out.open("div", cssClass = "section", id = sectionIdFor(section))
        section.children().forEach { child ->
            when (child.fb2Name()) {
                "title" -> title(child, depth, out)
                "section" -> if (withSubsections) section(child, depth + 1, out)
                else -> block(child, out)
            }
        }
        out.close("div")
    }

    /** A body's own title, epigraphs and image: what comes before its sections. */
    fun bodyHead(
        body: Element,
        out: Fb2Output,
    ) {
        body.children().forEach { child ->
            when (child.fb2Name()) {
                "title" -> title(child, depth = 0, out = out)
                "section" -> Unit
                else -> block(child, out)
            }
        }
    }

    /** A note from a notes body, as an EPUB footnote. */
    fun note(
        section: Element,
        out: Fb2Output,
    ) {
        val id = sectionIdFor(section)
        out.raw("<aside epub:type=\"footnote\" class=\"note\" id=\"").raw(id.escapeXml()).raw("\">")
        out.ids += id
        section.children().forEach { child ->
            when (child.fb2Name()) {
                "title" -> {
                    out.open("p", cssClass = "note-title")
                    inline.lines(child, out)
                    out.close("p")
                }
                "section" -> note(child, out)
                else -> block(child, out)
            }
        }
        out.raw("</aside>\n")
    }

    private fun title(
        title: Element,
        depth: Int,
        out: Fb2Output,
    ) {
        val level = (depth + 1).coerceIn(1, MAX_HEADING_LEVEL)
        out.open("h$level", cssClass = "title", id = title.attr("id"))
        inline.lines(title, out)
        out.close("h$level")
    }

    private fun block(
        element: Element,
        out: Fb2Output,
    ) {
        when (val name = element.fb2Name()) {
            "p" -> paragraph(element, out, cssClass = null)
            "subtitle", "text-author", "date" -> paragraph(element, out, cssClass = name)
            "v" -> paragraph(element, out, cssClass = "verse")
            "empty-line" -> out.raw("<p class=\"empty-line\">&#160;</p>\n")
            "image" -> blockImage(element, out)
            "epigraph", "cite", "annotation" -> container(element, out, tag = "blockquote", cssClass = name)
            "poem", "stanza" -> container(element, out, tag = "div", cssClass = name)
            "title" -> container(element, out, tag = "div", cssClass = "inner-title")
            "table" -> table(element, out)
            "section" -> section(element, depth = 1, out = out)
            else -> container(element, out, tag = "div", cssClass = null)
        }
    }

    private fun paragraph(
        element: Element,
        out: Fb2Output,
        cssClass: String?,
    ) {
        out.open("p", cssClass = cssClass, id = element.attr("id"))
        inline.children(element, out)
        out.close("p")
    }

    private fun container(
        element: Element,
        out: Fb2Output,
        tag: String,
        cssClass: String?,
    ) {
        out.open(tag, cssClass = cssClass, id = element.attr("id"))
        element.children().forEach { child ->
            if (child.fb2Name() == "title") {
                out.open("p", cssClass = "${cssClass ?: "block"}-title")
                inline.lines(child, out)
                out.close("p")
            } else {
                block(child, out)
            }
        }
        out.close(tag)
    }

    private fun table(
        element: Element,
        out: Fb2Output,
    ) {
        out.open("table", cssClass = null, id = element.attr("id"))
        element.childrenNamed("tr").forEach { row ->
            out.raw("<tr>")
            row.children().forEach { cell -> inline.tableCell(cell, out) }
            out.raw("</tr>\n")
        }
        out.close("table")
    }

    private fun blockImage(
        element: Element,
        out: Fb2Output,
    ) {
        val file = element.linkTarget()?.let(imageFileFor) ?: return
        out.open("div", cssClass = "image", id = element.attr("id"))
        out.raw("<img src=\"").raw(file.escapeXml()).raw("\" alt=\"").raw(element.attr("alt").escapeXml()).raw("\"/>")
        element.attr("title").takeIf(String::isNotBlank)?.let { caption ->
            out.raw("<p class=\"image-title\">").text(caption).raw("</p>")
        }
        out.close("div")
    }

    private companion object {
        const val MAX_HEADING_LEVEL = 6
    }
}

/** XHTML being written, and the anchors written into it. */
internal class Fb2Output {
    private val builder = StringBuilder()
    val ids = mutableListOf<String>()

    val length: Int get() = builder.length

    fun raw(text: String): Fb2Output = apply { builder.append(text) }

    fun text(text: String): Fb2Output = apply { builder.append(text.escapeXml()) }

    fun open(
        tag: String,
        cssClass: String? = null,
        id: String? = null,
    ): Fb2Output = apply {
        builder.append('<').append(tag)
        if (!id.isNullOrBlank()) {
            builder.append(" id=\"").append(id.escapeXml()).append('"')
            ids += id
        }
        if (cssClass != null) builder.append(" class=\"").append(cssClass).append('"')
        builder.append('>')
    }

    fun close(tag: String): Fb2Output = apply { builder.append("</").append(tag).append(">\n") }

    override fun toString(): String = builder.toString()
}

/** The placeholder a link to [id] carries until the chapter holding [id] is known. */
internal fun internalLinkHref(id: String): String = "$INTERNAL_LINK_SCHEME$id"

internal const val INTERNAL_LINK_SCHEME = "katana-link:"
