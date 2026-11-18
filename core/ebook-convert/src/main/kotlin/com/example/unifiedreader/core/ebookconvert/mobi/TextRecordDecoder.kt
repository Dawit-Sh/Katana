package com.example.unifiedreader.core.ebookconvert.mobi

import com.example.unifiedreader.core.ebookconvert.EbookConversionException

/** One text record's bytes made whole: none, PalmDOC (LZ77) or HUFF/CDIC compression. */
internal fun interface TextRecordDecoder {
    fun decode(record: ByteArray): ByteArray
}

/** The decoder for [header]'s compression. */
internal fun textRecordDecoder(
    header: MobiHeader,
    database: PalmDatabase,
): TextRecordDecoder = when (header.compression) {
    COMPRESSION_NONE -> TextRecordDecoder { it }
    COMPRESSION_PALMDOC -> TextRecordDecoder(::decompressPalmDoc)
    COMPRESSION_HUFF_CDIC -> {
        val huffman = HuffCdicDecoder(
            huff = database.record(header.huffmanRecord),
            cdics = (1 until header.huffmanRecordCount).map { database.record(header.huffmanRecord + it) },
        )
        TextRecordDecoder(huffman::decode)
    }
    else -> throw EbookConversionException(
        EbookConversionException.Reason.Unsupported,
        "Unknown text compression ${header.compression}",
    )
}

/** PalmDOC's LZ77 variant: literals, runs of literals, back references, and space + letter pairs. */
internal fun decompressPalmDoc(input: ByteArray): ByteArray {
    val written = ByteArray(input.size * PALMDOC_MAX_GROWTH + PALMDOC_MAX_COPY)
    var length = 0
    var index = 0
    fun put(byte: Int) {
        written[length] = byte.toByte()
        length += 1
    }
    while (index < input.size) {
        val code = input.u8(index)
        index += 1
        when {
            code == 0 || code in LITERAL_LOW..LITERAL_HIGH -> put(code)
            code in 1..LITERAL_RUN_MAX -> repeat(code) {
                if (index < input.size) put(input.u8(index))
                index += 1
            }
            code in BACK_REFERENCE_LOW..BACK_REFERENCE_HIGH -> {
                val next = if (index < input.size) input.u8(index) else 0
                index += 1
                val pair = ((code shl BITS_PER_BYTE_SHIFT) or next) and BACK_REFERENCE_MASK
                val distance = pair shr BACK_REFERENCE_LENGTH_BITS
                val count = (pair and BACK_REFERENCE_LENGTH_MASK) + BACK_REFERENCE_MIN_LENGTH
                // A reference before the start of the text (a damaged record) copies nothing.
                if (distance in 1..length) repeat(count) { put(written[length - distance].toInt() and BYTE_MASK) }
            }
            else -> {
                put(SPACE)
                put(code xor SPACE_PAIR_FLAG)
            }
        }
    }
    return written.copyOf(length)
}

/**
 * The bytes a text record carries after its text (multibyte overlap and index data, as
 * [extraDataFlags] says), which must go before it is decompressed.
 */
internal fun ByteArray.withoutTrailingEntries(extraDataFlags: Int): ByteArray {
    var trailing = 0
    var flags = extraDataFlags shr 1
    while (flags != 0) {
        if (flags and 1 != 0) trailing += trailingEntrySize(size - trailing)
        flags = flags shr 1
    }
    if (extraDataFlags and 1 != 0 && size - trailing - 1 >= 0) {
        trailing += (u8(size - trailing - 1) and MULTIBYTE_COUNT_MASK) + 1
    }
    return copyOfRange(0, (size - trailing).coerceIn(0, size))
}

// A trailing entry's size, written backwards from [end]: seven bits a byte, the first byte marked by its high bit.
private fun ByteArray.trailingEntrySize(end: Int): Int {
    var value = 0
    var shift = 0
    var position = end
    while (position > 0) {
        val byte = u8(position - 1)
        value = value or ((byte and VARINT_VALUE_BITS) shl shift)
        shift += VARINT_SHIFT
        position -= 1
        if (byte and VARINT_END_BIT != 0 || shift >= VARINT_MAX_SHIFT) break
    }
    return value
}

internal const val COMPRESSION_NONE = 1
internal const val COMPRESSION_PALMDOC = 2
internal const val COMPRESSION_HUFF_CDIC = 17480

private const val LITERAL_LOW = 0x09
private const val LITERAL_HIGH = 0x7F
private const val LITERAL_RUN_MAX = 0x08
private const val BACK_REFERENCE_LOW = 0x80
private const val BACK_REFERENCE_HIGH = 0xBF
private const val BACK_REFERENCE_MASK = 0x3FFF
private const val BACK_REFERENCE_LENGTH_BITS = 3
private const val BACK_REFERENCE_LENGTH_MASK = 0x07
private const val BACK_REFERENCE_MIN_LENGTH = 3
private const val BITS_PER_BYTE_SHIFT = 8
private const val SPACE = 0x20
private const val SPACE_PAIR_FLAG = 0x80
private const val PALMDOC_MAX_GROWTH = 8
private const val PALMDOC_MAX_COPY = 16
private const val MULTIBYTE_COUNT_MASK = 0x03
private const val VARINT_VALUE_BITS = 0x7F
private const val VARINT_SHIFT = 7
private const val VARINT_MAX_SHIFT = 28
private const val VARINT_END_BIT = 0x80
