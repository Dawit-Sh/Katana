package com.example.unifiedreader.core.ebookconvert.mobi

import java.nio.charset.Charset

/** One entry of a MOBI index: its key text and its tagged values. */
internal class MobiIndexEntry(
    val text: ByteArray,
    val tags: Map<Int, List<Long>>,
) {
    fun value(
        tag: Int,
        position: Int = 0,
    ): Long? = tags[tag]?.getOrNull(position)

    fun textAscii(): String = String(text, Charsets.ISO_8859_1)
}

/**
 * A MOBI index (INDX records): the KF8 skeleton and fragment tables and the table of contents
 * (NCX) are indexes. [labels] are its CNCX strings by offset.
 */
internal class MobiIndex(
    val entries: List<MobiIndexEntry>,
    private val labels: Map<Int, ByteArray>,
) {
    fun label(
        offset: Long?,
        charset: Charset,
    ): String? = offset?.let { labels[it.toInt()] }?.let { String(it, charset).trim() }?.takeIf(String::isNotEmpty)

    companion object {
        val Empty = MobiIndex(emptyList(), emptyMap())

        /** The index whose main INDX record is [record], or an empty one when there's none. */
        fun read(
            database: PalmDatabase,
            record: Int,
        ): MobiIndex {
            val main = database.recordOrNull(record)?.takeIf { it.ascii(0, MAGIC_LENGTH) == "INDX" }
            return main?.let { readIndex(database, record, it) } ?: Empty
        }

        private fun readIndex(
            database: PalmDatabase,
            record: Int,
            main: ByteArray,
        ): MobiIndex {
            val dataRecordCount = main.u32(COUNT_FIELD).toInt()
            val labelRecordCount = main.u32(LABEL_RECORDS_FIELD).toInt()
            val tagTable = TagTable.read(main, main.u32(HEADER_LENGTH_FIELD).toInt())
            val entries = (1..dataRecordCount).flatMap { offset ->
                database.recordOrNull(record + offset)?.let { tagTable.entriesIn(it) }.orEmpty()
            }
            val labels = HashMap<Int, ByteArray>()
            for (index in 0 until labelRecordCount) {
                val cncx = database.recordOrNull(record + dataRecordCount + 1 + index) ?: continue
                readLabels(cncx, index * LABEL_RECORD_SPAN, labels)
            }
            return MobiIndex(entries, labels)
        }

        private fun readLabels(
            cncx: ByteArray,
            base: Int,
            into: MutableMap<Int, ByteArray>,
        ) {
            var offset = 0
            while (offset < cncx.size && cncx[offset].toInt() != 0) {
                val (length, consumed) = cncx.forwardVarint(offset)
                val start = offset + consumed
                val end = (start + length.toInt()).coerceAtMost(cncx.size)
                into[base + offset] = cncx.copyOfRange(start, end)
                offset = end
            }
        }

        private const val MAGIC_LENGTH = 4
        private const val HEADER_LENGTH_FIELD = 4
        const val IDXT_OFFSET_FIELD = 20
        const val COUNT_FIELD = 24
        private const val LABEL_RECORDS_FIELD = 52
        private const val LABEL_RECORD_SPAN = 0x10000
    }
}

/** The TAGX table: which tags an entry may hold, how many values each, and their control-byte masks. */
private class TagTable(
    private val controlByteCount: Int,
    private val tags: List<TagDefinition>,
) {
    fun entriesIn(record: ByteArray): List<MobiIndexEntry> {
        val idxt = record.u32(MobiIndex.IDXT_OFFSET_FIELD).toInt()
        val count = record.u32(MobiIndex.COUNT_FIELD).toInt()
        if (record.ascii(0, MAGIC_LENGTH) != "INDX" || record.ascii(idxt, MAGIC_LENGTH) != "IDXT") return emptyList()
        val starts = (0 until count).map { index -> record.u16(idxt + MAGIC_LENGTH + index * 2) } + idxt
        return (0 until count).mapNotNull { index ->
            val start = starts[index]
            val end = starts[index + 1]
            if (start >= end || start >= record.size) return@mapNotNull null
            val textLength = record.u8(start)
            val text = record.copyOfRange(start + 1, (start + 1 + textLength).coerceAtMost(end))
            MobiIndexEntry(text, readValues(record, start + 1 + textLength, end))
        }
    }

    private fun readValues(
        record: ByteArray,
        start: Int,
        end: Int,
    ): Map<Int, List<Long>> {
        val present = mutableListOf<PresentTag>()
        var controlByteIndex = 0
        var position = start + controlByteCount
        for (tag in tags) {
            val found = if (tag.endFlag == 1) null else presentTag(tag, record.u8(start + controlByteIndex))
            if (tag.endFlag == 1) controlByteIndex += 1
            if (found?.byteCount != null) {
                // The byte count of a variable-width tag comes first, before every tag's values.
                val (byteCount, consumed) = record.forwardVarint(position)
                position += consumed
                present += found.copy(byteCount = byteCount.toInt())
            } else if (found != null) {
                present += found
            }
        }
        val values = LinkedHashMap<Int, List<Long>>()
        for (tag in present) {
            val read = mutableListOf<Long>()
            if (tag.valueCount != null) {
                repeat(tag.valueCount * tag.valuesPerEntry) {
                    if (position >= end) return@repeat
                    val (value, consumed) = record.forwardVarint(position)
                    read += value
                    position += consumed
                }
            } else {
                val stop = position + (tag.byteCount ?: 0)
                while (position < stop && position < end) {
                    val (value, consumed) = record.forwardVarint(position)
                    read += value
                    position += consumed
                }
            }
            values[tag.tag] = read
        }
        return values
    }

    // Whether [tag] is in this entry, and how many values or bytes it has, from its control byte.
    // All of a several-bit mask set means a byte count follows (read in order by the caller).
    private fun presentTag(
        tag: TagDefinition,
        controlByte: Int,
    ): PresentTag? {
        val value = controlByte and tag.mask
        return when {
            value == 0 -> null
            value == tag.mask && Integer.bitCount(tag.mask) > 1 ->
                PresentTag(tag.tag, tag.valuesPerEntry, valueCount = null, byteCount = -1)
            else -> PresentTag(
                tag.tag,
                tag.valuesPerEntry,
                valueCount = value shr Integer.numberOfTrailingZeros(tag.mask),
                byteCount = null,
            )
        }
    }

    companion object {
        private const val MAGIC_LENGTH = 4
        private const val ENTRY_SIZE = 4
        private const val FIRST_TAG_OFFSET = 12
        private const val CONTROL_BYTE_COUNT_FIELD = 8
        private const val TABLE_LENGTH_FIELD = 4

        fun read(
            main: ByteArray,
            offset: Int,
        ): TagTable {
            if (main.ascii(offset, MAGIC_LENGTH) != "TAGX") return TagTable(0, emptyList())
            val tableLength = main.u32(offset + TABLE_LENGTH_FIELD).toInt()
            val controlByteCount = main.u32(offset + CONTROL_BYTE_COUNT_FIELD).toInt()
            val tags = (FIRST_TAG_OFFSET until tableLength step ENTRY_SIZE).map { position ->
                val bytes = (0 until ENTRY_SIZE).map { main.u8(offset + position + it) }
                TagDefinition(tag = bytes[0], valuesPerEntry = bytes[1], mask = bytes[2], endFlag = bytes.last())
            }
            return TagTable(controlByteCount, tags)
        }
    }
}

private data class TagDefinition(
    val tag: Int,
    val valuesPerEntry: Int,
    val mask: Int,
    val endFlag: Int,
)

/** A tag in one entry: either [valueCount] groups of values, or values filling [byteCount] bytes. */
private data class PresentTag(
    val tag: Int,
    val valuesPerEntry: Int,
    val valueCount: Int?,
    val byteCount: Int?,
)
