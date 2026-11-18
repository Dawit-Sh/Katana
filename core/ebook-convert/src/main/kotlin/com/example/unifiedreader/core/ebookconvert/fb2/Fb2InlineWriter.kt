package com.example.unifiedreader.core.ebookconvert.fb2

import com.example.unifiedreader.core.ebookconvert.epub.escapeXml
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** Writes FB2 text with its inline markup (emphasis, links, note references, inline images) as XHTML. */
internal class Fb2InlineWriter(
    private val imageFileFor: (binaryId: String) -> String?,
) {
    /** A title's paragraphs, one line each. */
    fun lines(
        element: Element,
        out: Fb2Output,
    ) {
        val lines = element.children().filterNot { it.fb2Name() == "empty-line" }
        if (lines.isEmpty()) out.text(element.text())
        lines.forEachIndexed { index, line ->
            if (index > 0) out.raw("<br/>")
            node(line, out)
        }
    }

    fun children(
        element: Element,
        out: Fb2Output,
    ) {
        element.childNodes().forEach { child -> node(child, out) }
    }

    fun tableCell(
        cell: Element,
        out: Fb2Output,
    ) {
        val tag = if (cell.fb2Name() == "th") "th" else "td"
        out.raw("<").raw(tag)
        TableCellAttributes.forEach { name ->
            cell.attr(name).takeIf(String::isNotBlank)?.let { out.raw(" $name=\"").raw(it.escapeXml()).raw("\"") }
        }
        out.raw(">")
        children(cell, out)
        out.raw("</").raw(tag).raw(">")
    }

    private fun node(
        node: Node,
        out: Fb2Output,
    ) {
        when (node) {
            is TextNode -> out.text(node.wholeText)
            is Element -> element(node, out)
        }
    }

    private fun element(
        element: Element,
        out: Fb2Output,
    ) {
        val name = element.fb2Name()
        val tag = InlineTags[name]
        when {
            name == "a" -> link(element, out)
            name == "image" -> element.linkTarget()?.let(imageFileFor)?.let { file ->
                out.raw("<img class=\"inline\" src=\"").raw(file.escapeXml()).raw("\" alt=\"\"/>")
            }
            tag != null -> {
                out.raw("<").raw(tag).raw(">")
                children(element, out)
                out.raw("</").raw(tag).raw(">")
            }
            else -> children(element, out)
        }
    }

    private fun link(
        element: Element,
        out: Fb2Output,
    ) {
        val href = element.linkHref()
        if (href == null) {
            children(element, out)
            return
        }
        val target = if (href.startsWith("#")) internalLinkHref(href.removePrefix("#")) else href
        out.raw("<a")
        if (element.attr("type").equals("note", ignoreCase = true)) out.raw(" epub:type=\"noteref\" class=\"noteref\"")
        out.raw(" href=\"").raw(target.escapeXml()).raw("\">")
        children(element, out)
        out.raw("</a>")
    }

    private companion object {
        val InlineTags = mapOf(
            "strong" to "strong",
            "emphasis" to "em",
            "strikethrough" to "del",
            "sub" to "sub",
            "sup" to "sup",
            "code" to "code",
            "style" to "span",
        )
        val TableCellAttributes = listOf("colspan", "rowspan", "align", "valign")
    }
}
