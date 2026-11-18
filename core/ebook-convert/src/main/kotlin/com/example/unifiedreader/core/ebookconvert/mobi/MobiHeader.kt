package com.example.unifiedreader.core.ebookconvert.mobi

import java.nio.charset.Charset

/**
 * The header in a book part's first record: the PalmDOC header, the MOBI header and its EXTH
 * metadata. A combined MOBI holds two parts, the old MOBI 6 one and a KF8 one after a boundary
 * record; [start] is the record this header is in, and every record number here already counts
 * from the start of the file.
 */
internal class MobiHeader(
    private val record0: ByteArray,
    val start: Int,
) {
    val compression: Int = record0.u16(COMPRESSION_OFFSET)
    val textLength: Long = record0.u32(TEXT_LENGTH_OFFSET)
    val textRecordCount: Int = record0.u16(TEXT_RECORD_COUNT_OFFSET)
    val encryption: Int = record0.u16(ENCRYPTION_OFFSET)

    private val hasMobiHeader = record0.ascii(MOBI_MAGIC_OFFSET, MAGIC_LENGTH) == "MOBI"
    private val headerLength: Int = if (hasMobiHeader) record0.u32(HEADER_LENGTH_OFFSET).toInt() else 0

    val textEncoding: Int = field(ENCODING_OFFSET) ?: CODEPAGE_WINDOWS_1252
    val version: Int = field(VERSION_OFFSET) ?: 0
    val isKf8: Boolean = version >= KF8_VERSION
    val locale: Int = field(LOCALE_OFFSET) ?: 0
    val firstResourceRecord: Int = recordIndex(FIRST_RESOURCE_OFFSET)
    val huffmanRecord: Int = recordIndex(HUFFMAN_RECORD_OFFSET)
    val huffmanRecordCount: Int = field(HUFFMAN_COUNT_OFFSET) ?: 0
    val extraDataFlags: Int =
        if (headerLength >= EXTRA_FLAGS_MIN_HEADER && version >= EXTRA_FLAGS_MIN_VERSION) record0.u16(EXTRA_FLAGS_OFFSET) else 0
    val ncxIndex: Int = recordIndex(NCX_INDEX_OFFSET)
    val fdstRecord: Int = if (isKf8) recordIndex(FDST_OFFSET) else NOT_SET
    val fragmentIndex: Int = if (isKf8) recordIndex(FRAGMENT_INDEX_OFFSET) else NOT_SET
    val skeletonIndex: Int = if (isKf8) recordIndex(SKELETON_INDEX_OFFSET) else NOT_SET

    val charset: Charset = if (textEncoding == CODEPAGE_UTF8) Charsets.UTF_8 else windows1252()

    val exth: Exth = if (hasMobiHeader && (field(EXTH_FLAGS_OFFSET) ?: 0) and EXTH_PRESENT_FLAG != 0) {
        Exth.parse(record0, PALMDOC_HEADER_LENGTH + headerLength)
    } else {
        Exth(emptyMap())
    }

    /** The book's full name from the MOBI header, the title when EXTH has none. */
    val fullName: String? = run {
        val offset = field(FULL_NAME_OFFSET) ?: return@run null
        val length = field(FULL_NAME_LENGTH_OFFSET) ?: return@run null
        if (offset <= 0 || length <= 0 || offset + length > record0.size) return@run null
        String(record0, offset, length, charset).trim().takeIf(String::isNotEmpty)
    }

    // A 32-bit field of the MOBI header, or null when this header is too short to have it.
    private fun field(offset: Int): Int? {
        val end = offset + FIELD_SIZE
        val inHeader = hasMobiHeader && end <= PALMDOC_HEADER_LENGTH + headerLength && end <= record0.size
        return if (inHeader) record0.u32(offset).toInt() else null
    }

    private fun recordIndex(offset: Int): Int {
        val value = field(offset) ?: return NOT_SET
        return if (value == NOT_SET) NOT_SET else value + start
    }

    private companion object {
        const val COMPRESSION_OFFSET = 0
        const val TEXT_LENGTH_OFFSET = 4
        const val TEXT_RECORD_COUNT_OFFSET = 8
        const val ENCRYPTION_OFFSET = 12
        const val PALMDOC_HEADER_LENGTH = 16
        const val MOBI_MAGIC_OFFSET = 16
        const val MAGIC_LENGTH = 4
        const val HEADER_LENGTH_OFFSET = 20
        const val ENCODING_OFFSET = 0x1C
        const val VERSION_OFFSET = 0x24
        const val FULL_NAME_OFFSET = 0x54
        const val FULL_NAME_LENGTH_OFFSET = 0x58
        const val LOCALE_OFFSET = 0x5C
        const val FIRST_RESOURCE_OFFSET = 0x6C
        const val HUFFMAN_RECORD_OFFSET = 0x70
        const val HUFFMAN_COUNT_OFFSET = 0x74
        const val EXTH_FLAGS_OFFSET = 0x80
        const val FDST_OFFSET = 0xC0
        const val EXTRA_FLAGS_OFFSET = 0xF2
        const val NCX_INDEX_OFFSET = 0xF4
        const val FRAGMENT_INDEX_OFFSET = 0xF8
        const val SKELETON_INDEX_OFFSET = 0xFC
        const val EXTRA_FLAGS_MIN_HEADER = 0xE4
        const val EXTRA_FLAGS_MIN_VERSION = 5
        const val EXTH_PRESENT_FLAG = 0x40
        const val FIELD_SIZE = 4
        const val KF8_VERSION = 8
        const val CODEPAGE_UTF8 = 65001
        const val CODEPAGE_WINDOWS_1252 = 1252
    }
}

private fun windows1252(): Charset = runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)

/** The EXTH records: the book's metadata in a MOBI header, by record type. */
internal class Exth(private val records: Map<Int, List<ByteArray>>) {
    fun strings(
        type: Int,
        charset: Charset,
    ): List<String> = records[type].orEmpty().map { String(it, charset).trim() }.filter(String::isNotEmpty)

    fun string(
        type: Int,
        charset: Charset,
    ): String? = strings(type, charset).firstOrNull()

    fun int(type: Int): Int? = records[type]?.firstOrNull()?.takeIf { it.size >= INT_SIZE }?.u32(0)?.toInt()

    companion object {
        const val AUTHOR = 100
        const val PUBLISHER = 101
        const val DESCRIPTION = 103
        const val KF8_BOUNDARY = 121
        const val COVER_OFFSET = 201
        const val UPDATED_TITLE = 503
        const val LANGUAGE = 524
        const val PAGE_PROGRESSION = 527

        private const val INT_SIZE = 4
        private const val EXTH_HEADER_SIZE = 12
        private const val RECORD_HEADER_SIZE = 8

        fun parse(
            record0: ByteArray,
            offset: Int,
        ): Exth {
            val records = LinkedHashMap<Int, MutableList<ByteArray>>()
            var remaining = if (record0.ascii(offset, INT_SIZE) == "EXTH") record0.u32(offset + RECORD_HEADER_SIZE).toInt() else 0
            var position = offset + EXTH_HEADER_SIZE
            while (remaining > 0 && position + RECORD_HEADER_SIZE <= record0.size) {
                val type = record0.u32(position).toInt()
                val length = record0.u32(position + INT_SIZE).toInt()
                if (length < RECORD_HEADER_SIZE || position + length > record0.size) break
                records.getOrPut(type) { mutableListOf() } += record0.copyOfRange(position + RECORD_HEADER_SIZE, position + length)
                position += length
                remaining -= 1
            }
            return Exth(records)
        }
    }
}
