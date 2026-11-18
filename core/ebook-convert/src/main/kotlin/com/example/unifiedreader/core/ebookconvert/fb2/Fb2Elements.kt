package com.example.unifiedreader.core.ebookconvert.fb2

import com.example.unifiedreader.core.ebookconvert.EbookConversionException
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import org.jsoup.nodes.Element

/** The element's tag name without any namespace prefix. */
internal fun Element.fb2Name(): String = tagName().substringAfter(':')

internal fun Element.childrenNamed(name: String): List<Element> = children().filter { it.fb2Name() == name }

internal fun Element.firstChildNamed(name: String): Element? = children().firstOrNull { it.fb2Name() == name }

/** An `href` in any namespace (`l:href`, `xlink:href`), without its leading `#`. */
internal fun Element.linkTarget(): String? = linkHref()?.removePrefix("#")?.takeIf(String::isNotEmpty)

internal fun Element.linkHref(): String? = attributes().firstOrNull { attribute ->
    attribute.key == "href" || attribute.key.endsWith(":href")
}?.value?.trim()?.takeIf(String::isNotEmpty)

internal fun ByteArray.isZip(): Boolean =
    size > ZipSignature.size && ZipSignature.indices.all { index -> this[index] == ZipSignature[index] }

internal fun ByteArray.zipEntryNames(): List<String> = runCatching {
    ZipInputStream(ByteArrayInputStream(this)).use { zip ->
        generateSequence { zip.nextEntry }.map { it.name }.toList()
    }
}.getOrDefault(emptyList())

internal fun ByteArray.firstFb2InZip(): ByteArray = ZipInputStream(ByteArrayInputStream(this)).use { zip ->
    generateSequence { zip.nextEntry }
        .firstOrNull { entry -> !entry.isDirectory && entry.name.endsWith(".fb2", ignoreCase = true) }
        ?.let { zip.readBytes() }
} ?: throw EbookConversionException(EbookConversionException.Reason.NotThisFormat, "No FB2 file in this ZIP")

// "PK", 3, 4: a ZIP file's first local header.
private val ZipSignature = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 0x03, 0x04)
