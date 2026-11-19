package com.example.unifiedreader.feature.reader.epub

import java.io.ByteArrayInputStream
import java.util.IdentityHashMap
import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

/** Where words are in a chapter's text: from [start] up to, not including, [end]. */
internal data class EpubTextSpan(val start: Int, val end: Int)

/** The words of [span] and the text around them, as a Readium locator quotes them to find them again. */
internal data class EpubTextQuote(val before: String, val highlight: String, val after: String, val progression: Double)

/**
 * One chapter's document as a browser builds it from the file (an XHTML file as XML, an HTML one
 * as HTML), with its body's text in one string ([text], the body's `textContent`, which Readium's
 * selections quote) and where each piece of it sits in the document, so a place in the text and
 * an EPUB CFI path can be turned into each other.
 */
internal class EpubChapterText private constructor(
    private val document: Document,
    private val root: Element,
    val text: String,
    private val textStarts: IdentityHashMap<Node, Int>,
    private val elementSpans: IdentityHashMap<Element, EpubTextSpan>,
) {
    private val textNodes: List<Node> = textStarts.keys.filter { node -> node.textLength() > 0 }.sortedBy { textStarts.getValue(it) }
    private val squeezedText: SqueezedText by lazy { squeeze(text) }

    /** The text the CFI [range]'s start and end paths point at, or null when they point elsewhere (not the body). */
    fun spanOf(range: EpubCfiRange): EpubTextSpan? {
        val start = offsetOf(range.start, isEnd = false)
        val end = offsetOf(range.end, isEnd = true)
        return if (start != null && end != null) text.trimmedSpan(start, end) else null
    }

    /** The CFI paths from the root element to the start and end of [span]. */
    fun cfiPaths(span: EpubTextSpan): Pair<List<CfiStep>, List<CfiStep>>? {
        val startNode = textNodes.lastOrNull { node -> textStarts.getValue(node) <= span.start }
        val endNode = textNodes.firstOrNull { node -> textStarts.getValue(node) + node.textLength() >= span.end }
        val start = startNode?.let { node -> pathTo(node, span.start - textStarts.getValue(node)) }
        val end = endNode?.let { node -> pathTo(node, span.end - textStarts.getValue(node)) }
        return if (span.start < span.end && start != null && end != null) start to end else null
    }

    /**
     * Where the quoted [highlight] is: the occurrence whose text before and after best matches
     * [before] and [after] (Readium's quote), then the one nearest [near] (see [bestOccurrence]).
     */
    fun find(highlight: String, before: String?, after: String?, near: Int? = null): EpubTextSpan? {
        val needle = squeeze(highlight).text
        val wantedBefore = before?.let { squeeze(it).text }.orEmpty()
        val wantedAfter = after?.let { squeeze(it).text }.orEmpty()
        val found = needle.takeIf(String::isNotEmpty)?.let { bestOccurrence(squeezedText, it, wantedBefore, wantedAfter, near) }
        return found?.let { at -> EpubTextSpan(squeezedText.origin[at], squeezedText.origin[at + needle.length - 1] + 1) }
    }

    /** The words of [span] with the text before and after them, and how far into the chapter they start. */
    fun quote(span: EpubTextSpan): EpubTextQuote = EpubTextQuote(
        before = text.substring((span.start - QUOTE_CONTEXT).coerceAtLeast(0), span.start),
        highlight = text.substring(span.start, span.end),
        after = text.substring(span.end, (span.end + QUOTE_CONTEXT).coerceAtMost(text.length)),
        progression = if (text.isEmpty()) 0.0 else span.start.toDouble() / text.length,
    )

    // The text offset a path points at: in a text step, its offset; at an element, where it starts (or
    // ends). As foliate-js, a last step that asserts the id of an element there goes to that element.
    private fun offsetOf(steps: List<CfiStep>, isEnd: Boolean): Int? {
        val byId = steps.last().id?.let(document::getElementById)
        return if (byId != null) elementSpans[byId]?.edge(isEnd) else offsetFrom(root, steps, isEnd)
    }

    private fun offsetFrom(element: Element, steps: List<CfiStep>, isEnd: Boolean): Int? {
        val step = steps.first()
        val rest = steps.drop(1)
        val span = spanOf(element)
        val children = element.children()
        return when {
            span == null -> null
            step.index % 2 == 1 -> if (rest.isEmpty()) chunkOffset(element, (step.index - 1) / 2, step.offset ?: 0) else null
            step.index == 0 -> span.start
            step.index / 2 > children.size -> span.end
            rest.isEmpty() -> spanOf(children[step.index / 2 - 1])?.edge(isEnd)
            else -> offsetFrom(children[step.index / 2 - 1], rest, isEnd)
        }
    }

    // The text between an element's children [chunk] - 1 and [chunk] (0: before the first), [offset] into it.
    private fun chunkOffset(element: Element, chunk: Int, offset: Int): Int? {
        val children = element.children()
        val start = when {
            chunk == 0 -> spanOf(element)?.start
            chunk <= children.size -> spanOf(children[chunk - 1])?.end
            else -> null
        }
        val end = when {
            chunk < children.size -> spanOf(children[chunk])?.start
            chunk == children.size -> spanOf(element)?.end
            else -> null
        }
        return if (start != null && end != null) (start + offset).coerceIn(start, maxOf(start, end)) else null
    }

    private fun spanOf(element: Element): EpubTextSpan? =
        elementSpans[element] ?: if (element === root) EpubTextSpan(0, text.length) else null

    // The steps from the root to a text node, [offset] into it.
    private fun pathTo(textNode: Node, offset: Int): List<CfiStep>? {
        val parent = textNode.parentNode() as? Element ?: return null
        return elementPath(parent)?.let { path -> path + textStep(parent, textNode, offset) }
    }

    // The steps from the root to [element] (none for the root itself); null when it isn't under the root.
    private fun elementPath(element: Element): List<CfiStep>? {
        if (element === root) return emptyList()
        val step = CfiStep(index = 2 * (element.elementSiblingIndex() + 1), id = element.id().takeIf(String::isNotEmpty))
        return (element.parentNode() as? Element)?.let(::elementPath)?.plus(step)
    }

    companion object {
        /** The chapter in [bytes]; [isHtml] for an HTML (not XHTML) file. Null when it has no root element. */
        fun parse(bytes: ByteArray, isHtml: Boolean = false): EpubChapterText? {
            val parser = if (isHtml) Parser.htmlParser() else Parser.xmlParser()
            val document = Jsoup.parse(ByteArrayInputStream(bytes), null, "", parser)
            return document.children().firstOrNull()?.let { root -> read(document, root) }
        }

        private fun read(document: Document, root: Element): EpubChapterText {
            val body = root.children().firstOrNull { it.localName() == "body" }
            val text = StringBuilder()
            val textStarts = IdentityHashMap<Node, Int>()
            val elementStarts = IdentityHashMap<Element, Int>()
            val elementSpans = IdentityHashMap<Element, EpubTextSpan>()
            if (body != null) {
                NodeTraversor.traverse(
                    object : NodeVisitor {
                        override fun head(node: Node, depth: Int) {
                            when {
                                node is Element -> elementStarts[node] = text.length
                                node.isText() -> {
                                    textStarts[node] = text.length
                                    text.append(node.textOf())
                                }
                            }
                        }

                        override fun tail(node: Node, depth: Int) {
                            if (node is Element) elementSpans[node] = EpubTextSpan(elementStarts.getValue(node), text.length)
                        }
                    },
                    body,
                )
            }
            return EpubChapterText(document, root, text.toString(), textStarts, elementSpans)
        }

        private const val QUOTE_CONTEXT = 200
    }
}

// The CFI step of a text node, [offset] into it: the node's text and the text nodes before it since
// the last element sibling make one piece, counted as one.
private fun textStep(parent: Element, textNode: Node, offset: Int): CfiStep {
    var elementsBefore = 0
    var pieceStart = 0
    for (sibling in parent.childNodes()) {
        if (sibling === textNode) break
        when {
            sibling is Element -> {
                elementsBefore += 1
                pieceStart = 0
            }
            sibling.isText() -> pieceStart += sibling.textLength()
        }
    }
    return CfiStep(index = 2 * elementsBefore + 1, offset = pieceStart + offset)
}

/** Whether [span] holds [words], spaces and line breaks aside. */
internal fun EpubChapterText.holds(span: EpubTextSpan, words: String): Boolean =
    squeeze(text.substring(span.start, span.end)).text == squeeze(words).text

private fun EpubTextSpan.edge(isEnd: Boolean): Int = if (isEnd) end else start

// [start, end) of this text without the spaces at either end; null when nothing is left.
private fun String.trimmedSpan(start: Int, end: Int): EpubTextSpan? {
    var from = start.coerceIn(0, length)
    var to = end.coerceIn(0, length)
    while (from < to && this[from].isIgnorable()) from += 1
    while (to > from && this[to - 1].isIgnorable()) to -= 1
    return if (from < to) EpubTextSpan(from, to) else null
}

private fun Node.isText(): Boolean = this is TextNode || this is DataNode

private fun Node.textOf(): String = when (this) {
    is TextNode -> wholeText
    is DataNode -> wholeData
    else -> ""
}

private fun Node.textLength(): Int = textOf().length

// The element's name without a namespace prefix, as `body` in `<html:body>`.
private fun Element.localName(): String = tagName().substringAfter(':').lowercase()
