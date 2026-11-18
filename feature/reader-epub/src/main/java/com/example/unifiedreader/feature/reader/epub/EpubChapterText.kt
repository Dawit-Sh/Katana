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

    /** The text the CFI [range]'s start and end paths point at, or null when they point elsewhere (not the body). */
    fun spanOf(range: EpubCfiRange): EpubTextSpan? {
        val start = offsetOf(range.start, isEnd = false) ?: return null
        val end = offsetOf(range.end, isEnd = true) ?: return null
        return trimmed(start, end)
    }

    /** The CFI paths from the root element to the start and end of [span]. */
    fun cfiPaths(span: EpubTextSpan): Pair<List<CfiStep>, List<CfiStep>>? {
        if (span.start >= span.end) return null
        val startNode = textNodes.lastOrNull { node -> textStarts.getValue(node) <= span.start } ?: return null
        val endNode = textNodes.firstOrNull { node -> textStarts.getValue(node) + node.textLength() >= span.end } ?: return null
        val start = pathTo(startNode, span.start - textStarts.getValue(startNode)) ?: return null
        val end = pathTo(endNode, span.end - textStarts.getValue(endNode)) ?: return null
        return start to end
    }

    /**
     * Where the quoted [highlight] is, the occurrence whose text before and after best matches
     * [before] and [after] (Readium's quote). Spaces, line breaks and soft hyphens don't count: a
     * selection's text breaks lines between paragraphs where the document may have none.
     */
    fun find(highlight: String, before: String?, after: String?): EpubTextSpan? {
        val needle = squeeze(highlight).text
        if (needle.isEmpty()) return null
        val haystack = squeezedText
        val wantedBefore = before?.let { squeeze(it).text }.orEmpty()
        val wantedAfter = after?.let { squeeze(it).text }.orEmpty()
        var best: Int? = null
        var bestScore = -1
        var at = haystack.text.indexOf(needle)
        while (at >= 0) {
            val score = commonSuffix(haystack.text, at, wantedBefore) + commonPrefix(haystack.text, at + needle.length, wantedAfter)
            if (score > bestScore) {
                best = at
                bestScore = score
            }
            at = haystack.text.indexOf(needle, at + 1)
        }
        val found = best ?: return null
        return EpubTextSpan(haystack.origin[found], haystack.origin[found + needle.length - 1] + 1)
    }

    /** The words of [span] with the text before and after them, and how far into the chapter they start. */
    fun quote(span: EpubTextSpan): EpubTextQuote = EpubTextQuote(
        before = text.substring((span.start - QUOTE_CONTEXT).coerceAtLeast(0), span.start),
        highlight = text.substring(span.start, span.end),
        after = text.substring(span.end, (span.end + QUOTE_CONTEXT).coerceAtMost(text.length)),
        progression = if (text.isEmpty()) 0.0 else span.start.toDouble() / text.length,
    )

    private val squeezedText: Squeezed by lazy { squeeze(text) }

    // The text offset a path points at: in a text step, its offset; at an element, where it starts (or ends).
    private fun offsetOf(steps: List<CfiStep>, isEnd: Boolean): Int? {
        steps.last().id?.let { id ->
            document.getElementById(id)?.let { element ->
                val span = elementSpans[element] ?: return null
                return if (isEnd) span.end else span.start
            }
        }
        var element = root
        steps.forEachIndexed { position, step ->
            val isLast = position == steps.lastIndex
            if (step.index % 2 == 1) return if (isLast) chunkOffset(element, (step.index - 1) / 2, step.offset ?: 0) else null
            val span = spanOf(element) ?: return null
            val children = element.children()
            when {
                step.index == 0 -> return span.start
                step.index / 2 > children.size -> return span.end
            }
            element = children[step.index / 2 - 1]
            if (isLast) {
                val target = spanOf(element) ?: return null
                return if (isEnd) target.end else target.start
            }
        }
        return null
    }

    // The text between an element's children [chunk] - 1 and [chunk] (0: before the first), [offset] into it.
    private fun chunkOffset(element: Element, chunk: Int, offset: Int): Int? {
        val span = spanOf(element) ?: return null
        val children = element.children()
        if (chunk > children.size) return null
        val start = if (chunk == 0) span.start else spanOf(children[chunk - 1])?.end ?: return null
        val end = if (chunk < children.size) spanOf(children[chunk])?.start ?: return null else span.end
        return (start + offset).coerceIn(start, end)
    }

    private fun spanOf(element: Element): EpubTextSpan? =
        elementSpans[element] ?: if (element === root) EpubTextSpan(0, text.length) else null

    // The steps from the root to a text node, [offset] into it (counted the CFI way: the node's text
    // and the text nodes before it since the last element sibling make one piece).
    private fun pathTo(textNode: Node, offset: Int): List<CfiStep>? {
        val parent = textNode.parentNode() as? Element ?: return null
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
        val steps = ArrayDeque<CfiStep>()
        steps.addFirst(CfiStep(index = 2 * elementsBefore + 1, offset = pieceStart + offset))
        var element = parent
        while (element !== root) {
            val above = element.parentNode() as? Element ?: return null
            steps.addFirst(CfiStep(index = 2 * (element.elementSiblingIndex() + 1), id = element.id().takeIf(String::isNotEmpty)))
            element = above
        }
        return steps.toList()
    }

    // [start, end) without the spaces at either end.
    private fun trimmed(start: Int, end: Int): EpubTextSpan? {
        var from = start.coerceIn(0, text.length)
        var to = end.coerceIn(0, text.length)
        while (from < to && text[from].isIgnorable()) from += 1
        while (to > from && text[to - 1].isIgnorable()) to -= 1
        return if (from < to) EpubTextSpan(from, to) else null
    }

    companion object {
        /** The chapter in [bytes]; [isHtml] for an HTML (not XHTML) file. Null when it has no root element. */
        fun parse(bytes: ByteArray, isHtml: Boolean = false): EpubChapterText? {
            val parser = if (isHtml) Parser.htmlParser() else Parser.xmlParser()
            val document = Jsoup.parse(ByteArrayInputStream(bytes), null, "", parser)
            val root = document.children().firstOrNull() ?: return null
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

// The text a selection quote can differ in: spaces, line breaks, soft hyphens and zero-width marks.
private fun Char.isIgnorable(): Boolean = isWhitespace() || this == ' ' || this == '­' || this in '​'..'‍' || this == '﻿'

/** [text] without its ignorable characters; [origin] is where each kept character was in the original. */
private class Squeezed(val text: String, val origin: IntArray)

private fun squeeze(value: String): Squeezed {
    val kept = StringBuilder(value.length)
    val origin = IntArray(value.length)
    value.forEachIndexed { index, char ->
        if (!char.isIgnorable()) {
            origin[kept.length] = index
            kept.append(char)
        }
    }
    return Squeezed(kept.toString(), origin.copyOf(kept.length))
}

private fun commonSuffix(text: String, end: Int, wanted: String): Int {
    var count = 0
    while (count < wanted.length && end - count - 1 >= 0 && text[end - count - 1] == wanted[wanted.length - count - 1]) count += 1
    return count
}

private fun commonPrefix(text: String, start: Int, wanted: String): Int {
    var count = 0
    while (count < wanted.length && start + count < text.length && text[start + count] == wanted[count]) count += 1
    return count
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
