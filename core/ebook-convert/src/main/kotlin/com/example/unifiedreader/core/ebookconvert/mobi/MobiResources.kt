package com.example.unifiedreader.core.ebookconvert.mobi

import com.example.unifiedreader.core.ebookconvert.epub.EpubResource
import com.example.unifiedreader.core.ebookconvert.imageExtension
import com.example.unifiedreader.core.ebookconvert.sniffImageMediaType
import java.util.zip.Inflater

/**
 * The images and fonts a book uses, by resource number (counted from one), each written out once
 * and only when something refers to it.
 */
internal class MobiResources(private val book: MobiBook) {
    private val loaded = LinkedHashMap<Int, EpubResource?>()

    /** The EPUB file name for resource [number], or null when it isn't an image or font. */
    fun fileFor(number: Int): String? = loaded.getOrPut(number) { load(number) }?.fileName

    val resources: List<EpubResource>
        get() = loaded.values.filterNotNull()

    private fun load(number: Int): EpubResource? {
        val bytes = book.resource(number) ?: return null
        val mediaType = sniffImageMediaType(bytes)
        return when {
            mediaType != null -> EpubResource("image%05d.%s".format(number, imageExtension(mediaType)), mediaType, bytes)
            bytes.ascii(0, MAGIC_LENGTH) == "FONT" -> decodeFont(bytes)?.let { font -> fontResource(number, font) }
            else -> null
        }
    }

    private fun fontResource(
        number: Int,
        font: ByteArray,
    ): EpubResource {
        val isOpenType = font.ascii(0, MAGIC_LENGTH) == "OTTO"
        return EpubResource(
            fileName = "font%05d.%s".format(number, if (isOpenType) "otf" else "ttf"),
            mediaType = if (isOpenType) "font/otf" else "font/ttf",
            bytes = font,
        )
    }

    private companion object {
        const val MAGIC_LENGTH = 4
    }
}

/** A KF8 FONT record's font: its first bytes may be XOR-obfuscated, and the font zlib-compressed. */
internal fun decodeFont(record: ByteArray): ByteArray? = runCatching {
    val flags = record.u32(FONT_FLAGS_FIELD).toInt()
    val dataStart = record.u32(FONT_DATA_START_FIELD).toInt()
    val xorLength = record.u32(FONT_XOR_LENGTH_FIELD).toInt()
    val xorStart = record.u32(FONT_XOR_START_FIELD).toInt()
    val data = record.copyOfRange(dataStart, record.size)
    if (flags and FONT_FLAG_OBFUSCATED != 0 && xorLength > 0) {
        val key = record.copyOfRange(xorStart, xorStart + xorLength)
        for (index in 0 until minOf(data.size, FONT_OBFUSCATED_LENGTH)) {
            data[index] = (data[index].toInt() xor key[index % xorLength].toInt()).toByte()
        }
    }
    if (flags and FONT_FLAG_COMPRESSED != 0) inflate(data) else data
}.getOrNull()

private fun inflate(data: ByteArray): ByteArray {
    val inflater = Inflater()
    try {
        inflater.setInput(data)
        val output = java.io.ByteArrayOutputStream(data.size * 2)
        val buffer = ByteArray(INFLATE_BUFFER)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    } finally {
        inflater.end()
    }
}

private const val FONT_FLAGS_FIELD = 8
private const val FONT_DATA_START_FIELD = 12
private const val FONT_XOR_LENGTH_FIELD = 16
private const val FONT_XOR_START_FIELD = 20
private const val FONT_FLAG_COMPRESSED = 1
private const val FONT_FLAG_OBFUSCATED = 2
private const val FONT_OBFUSCATED_LENGTH = 1040
private const val INFLATE_BUFFER = 16 * 1024
