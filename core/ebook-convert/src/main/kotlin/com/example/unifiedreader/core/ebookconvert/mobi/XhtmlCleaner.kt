package com.example.unifiedreader.core.ebookconvert.mobi

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities

/**
 * Makes a parsed Kindle document safe to serve as XHTML: a WebView refuses a whole chapter over
 * one undeclared namespace prefix or unknown entity, where Kindles shrug. Removes Kindle-only
 * tags and attributes and scripts, declares the SVG namespaces, and sets XML output.
 */
internal fun Document.cleanForXhtml(): Document {
    outputSettings()
        .syntax(Document.OutputSettings.Syntax.xml)
        .escapeMode(Entities.EscapeMode.xhtml)
        .charset(Charsets.UTF_8)
        .prettyPrint(false)
    getElementsByTag("script").remove()
    getElementsByTag("guide").remove()
    // Iterate over a copy: unwrapping changes the tree.
    getAllElements().toList().forEach { element -> cleanElement(element) }
    return this
}

private fun cleanElement(element: Element) {
    val name = element.tagName()
    when {
        name.equals("mbp:pagebreak", ignoreCase = true) -> {
            element.remove()
            return
        }
        name.contains(':') && name.substringBefore(':').lowercase() !in KeptPrefixes -> {
            element.unwrap()
            return
        }
    }
    element.attributes().asList().map { it.key }.forEach { key ->
        val prefix = key.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (prefix.isNotEmpty() && prefix !in KeptPrefixes && prefix != "xmlns") element.removeAttr(key)
    }
    // Kindlegen keeps an EPUB's srcset but not the files it names; the WebView would pick those over src.
    element.removeAttr("srcset")
    if (name.equals("svg", ignoreCase = true)) declareSvgNamespaces(element)
    if (name.equals("math", ignoreCase = true) && !element.hasAttr("xmlns")) element.attr("xmlns", MATHML_NAMESPACE)
    SvgAttributeCase.forEach { (lower, proper) ->
        if (element.hasAttr(lower) && !element.attributes().hasKey(proper)) {
            val value = element.attr(lower)
            element.removeAttr(lower)
            element.attr(proper, value)
        }
    }
}

private fun declareSvgNamespaces(svg: Element) {
    if (!svg.hasAttr("xmlns")) svg.attr("xmlns", SVG_NAMESPACE)
    val usesXlink = svg.getAllElements().any { child -> child.attributes().any { it.key.startsWith("xlink:") } }
    if (usesXlink && !svg.hasAttr("xmlns:xlink")) svg.attr("xmlns:xlink", XLINK_NAMESPACE)
}

/** The serialized document as XHTML: its `html` element after an XML declaration and doctype. */
internal fun Document.toXhtmlString(): String {
    val html = selectFirst("html") ?: return ""
    if (!html.hasAttr("xmlns")) html.attr("xmlns", XHTML_NAMESPACE)
    if (html.outerHtml().contains("epub:") && !html.hasAttr("xmlns:epub")) html.attr("xmlns:epub", EPUB_NAMESPACE)
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE html>\n" + html.outerHtml().withoutXmlForbiddenChars()
}

private fun String.withoutXmlForbiddenChars(): String = replace(XmlForbiddenChars, "")

private val XmlForbiddenChars = Regex("""[\x00-\x08\x0B\x0C\x0E-\x1F\uFFFE\uFFFF]""")

private val KeptPrefixes = setOf("xml", "xlink", "epub", "svg")

// HTML parsing lowercases attribute names; SVG needs these back in their own case to work.
private val SvgAttributeCase = listOf(
    "viewbox" to "viewBox",
    "preserveaspectratio" to "preserveAspectRatio",
)

internal const val XHTML_NAMESPACE = "http://www.w3.org/1999/xhtml"
private const val EPUB_NAMESPACE = "http://www.idpf.org/2007/ops"
private const val SVG_NAMESPACE = "http://www.w3.org/2000/svg"
private const val XLINK_NAMESPACE = "http://www.w3.org/1999/xlink"
private const val MATHML_NAMESPACE = "http://www.w3.org/1998/Math/MathML"
