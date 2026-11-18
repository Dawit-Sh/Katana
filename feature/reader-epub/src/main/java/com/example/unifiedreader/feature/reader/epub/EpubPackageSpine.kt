package com.example.unifiedreader.feature.reader.epub

import java.io.ByteArrayInputStream
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/** A spine item: its file's path from the book's root (decoded, no fragment) and its CFI steps in the package. */
internal data class EpubSpineItem(val path: String, val packageSteps: List<CfiStep>)

/**
 * The package's spine, every item of it (Readium's reading order leaves out the non-linear ones,
 * which a CFI still counts), with the steps a CFI takes to each: the spine element's place in the
 * package (6, after the metadata and the manifest) and the item's place in the spine, with their ids.
 */
internal data class EpubPackageSpine(val items: List<EpubSpineItem>) {
    fun indexOf(path: String): Int = items.indexOfFirst { it.path == path }.takeIf { it >= 0 }
        ?: items.indexOfFirst { item -> item.path.endsWith("/$path") || path.endsWith("/${item.path}") }

    companion object {
        /** The root file named in `META-INF/container.xml`. */
        fun packagePath(container: ByteArray): String? =
            parseXml(container).allElements
                .filter { it.localName() == "rootfile" }
                .firstNotNullOfOrNull { it.attr("full-path").takeIf(String::isNotBlank) }

        /** The spine of the package document [opf], found at [opfPath] in the book. */
        fun parse(opf: ByteArray, opfPath: String): EpubPackageSpine? {
            val packageElement = parseXml(opf).children().firstOrNull() ?: return null
            val spine = packageElement.children().firstOrNull { it.localName() == "spine" } ?: return null
            val manifest = packageElement.children().firstOrNull { it.localName() == "manifest" }
            val hrefById = manifest?.children().orEmpty()
                .filter { it.localName() == "item" }
                .associate { item -> item.attr("id") to item.attr("href") }
            val spineStep = CfiStep(index = 2 * (spine.elementSiblingIndex() + 1), id = spine.id().takeIf(String::isNotEmpty))
            val directory = opfPath.substringBeforeLast('/', missingDelimiterValue = "")
            val items = spine.children().mapIndexedNotNull { position, itemref ->
                val href = hrefById[itemref.attr("idref")] ?: return@mapIndexedNotNull null
                EpubSpineItem(
                    path = resolvePath(directory, href),
                    packageSteps = listOf(
                        spineStep,
                        CfiStep(index = 2 * (position + 1), id = itemref.id().takeIf(String::isNotEmpty)),
                    ),
                )
            }
            return EpubPackageSpine(items)
        }

        private fun parseXml(bytes: ByteArray) = Jsoup.parse(ByteArrayInputStream(bytes), null, "", Parser.xmlParser())
    }
}

/** [href] (percent-encoded, relative to [directory]) as a decoded path from the book's root. */
internal fun resolvePath(directory: String, href: String): String {
    val decoded = percentDecode(href.substringBefore('#'))
    val segments = ArrayDeque<String>()
    (if (directory.isEmpty()) decoded else "$directory/$decoded").split('/').forEach { segment ->
        when (segment) {
            "", "." -> Unit
            ".." -> segments.removeLastOrNull()
            else -> segments.addLast(segment)
        }
    }
    return segments.joinToString("/")
}

// Percent-escapes as UTF-8; a `+` stays a `+` (it is not a space in a path).
private fun percentDecode(value: String): String {
    if ('%' !in value) return value
    val bytes = java.io.ByteArrayOutputStream()
    var position = 0
    while (position < value.length) {
        val char = value[position]
        val escaped = if (char == '%' && position + 2 <= value.lastIndex) {
            value.substring(position + 1, position + 3).toIntOrNull(HEX_RADIX)
        } else {
            null
        }
        if (escaped != null) {
            bytes.write(escaped)
            position += 3
        } else {
            bytes.write(char.toString().toByteArray(Charsets.UTF_8))
            position += 1
        }
    }
    return bytes.toString(Charsets.UTF_8.name())
}

private const val HEX_RADIX = 16

private fun Element.localName(): String = tagName().substringAfter(':').lowercase()
