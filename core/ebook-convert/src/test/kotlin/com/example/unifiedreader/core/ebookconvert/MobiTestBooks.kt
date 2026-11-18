package com.example.unifiedreader.core.ebookconvert

import java.io.ByteArrayOutputStream
import java.util.Base64

/** Builds small MOBI 6 and KF8 files byte by byte, the way Kindlegen lays them out. */
internal object MobiTestBooks {
    val TinyPng: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
    )

    /** A Palm database holding [records]. */
    fun palmDatabase(
        records: List<ByteArray>,
        type: String = "BOOKMOBI",
    ): ByteArray {
        val header = ByteArray(PDB_HEADER + records.size * 8 + 2)
        "Test book".toByteArray().copyInto(header)
        type.toByteArray(Charsets.US_ASCII).copyInto(header, 60)
        header.put16(76, records.size)
        var offset = header.size
        records.forEachIndexed { index, record ->
            header.put32(PDB_HEADER + index * 8, offset.toLong())
            header.put32(PDB_HEADER + index * 8 + 4, index.toLong() * 2)
            offset += record.size
        }
        return header + records.fold(ByteArray(0)) { all, record -> all + record }
    }

    /** What [record0] writes; [fields] are extra 32-bit header fields by offset. */
    data class Record0(
        val textLength: Int,
        val textRecordCount: Int,
        val compression: Int = 1,
        val encryption: Int = 0,
        val version: Int = 6,
        val extraFlags: Int = 0,
        val firstImage: Int = -1,
        val exth: List<Pair<Int, ByteArray>> = emptyList(),
        val fields: Map<Int, Int> = emptyMap(),
    )

    /** A record 0: PalmDOC header, MOBI header and EXTH. Unset fields are 0xFFFFFFFF. */
    fun record0(spec: Record0): ByteArray = with(spec) {
        val headerLength = if (version >= 8) 0x108 else 0xE8
        val mobi = ByteArray(16 + headerLength) { if (it >= 16) 0xFF.toByte() else 0 }
        mobi.put16(0, compression)
        mobi.put32(4, textLength.toLong())
        mobi.put16(8, textRecordCount)
        mobi.put16(10, 4096)
        mobi.put16(12, encryption)
        "MOBI".toByteArray().copyInto(mobi, 16)
        mobi.put32(0x14, headerLength.toLong())
        mobi.put32(0x18, 2)
        mobi.put32(0x1C, 65001)
        mobi.put32(0x24, version.toLong())
        mobi.put32(0x5C, 9)
        mobi.put32(0x6C, firstImage.toLong() and 0xFFFFFFFFL)
        mobi.put32(0x74, 0)
        mobi.put32(0x80, if (exth.isEmpty()) 0 else 0x40)
        mobi.put32(0xF0, extraFlags.toLong())
        fields.forEach { (offset, value) -> mobi.put32(offset, value.toLong() and 0xFFFFFFFFL) }
        val exthBytes = exthBlock(exth)
        val fullName = "Full Name".toByteArray()
        val record = mobi + exthBytes + fullName + ByteArray(4)
        record.put32(0x54, (mobi.size + exthBytes.size).toLong())
        record.put32(0x58, fullName.size.toLong())
        record
    }

    private fun exthBlock(records: List<Pair<Int, ByteArray>>): ByteArray {
        if (records.isEmpty()) return ByteArray(0)
        val body = ByteArrayOutputStream()
        records.forEach { (type, data) ->
            body.write(int32(type.toLong()))
            body.write(int32(data.size + 8L))
            body.write(data)
        }
        val content = body.toByteArray()
        val padding = (4 - (content.size + 12) % 4) % 4
        return "EXTH".toByteArray() + int32(content.size + 12L + padding) + int32(records.size.toLong()) + content + ByteArray(padding)
    }

    fun exthInt(value: Int): ByteArray = int32(value.toLong())

    /** [text] split into text records of up to 4096 bytes, each with [trailer] after it. */
    fun textRecords(
        text: ByteArray,
        trailer: ByteArray = ByteArray(0),
    ): List<ByteArray> = text.toList().chunked(4096).map { it.toByteArray() + trailer }

    /** A MOBI index: its main INDX record (with [tagx]), one record of [entries], and the CNCX [labels] record. */
    fun index(
        tagx: List<IntArray>,
        controlByteCount: Int,
        entries: List<Pair<String, ByteArray>>,
        labels: List<String> = emptyList(),
    ): List<ByteArray> {
        val tagTable = ByteArrayOutputStream().apply {
            write("TAGX".toByteArray())
            write(int32(12L + tagx.size * 4))
            write(int32(controlByteCount.toLong()))
            tagx.forEach { tag -> tag.forEach { write(it) } }
        }.toByteArray()
        val main = indxHeader(start = 0, count = 1, cncxCount = if (labels.isEmpty()) 0 else 1) + tagTable
        val body = ByteArrayOutputStream()
        val starts = mutableListOf<Int>()
        entries.forEach { (text, values) ->
            starts += INDX_HEADER + body.size()
            val key = text.toByteArray(Charsets.US_ASCII)
            body.write(key.size)
            body.write(key)
            body.write(values)
        }
        val idxtStart = INDX_HEADER + body.size()
        val idxt = ByteArrayOutputStream().apply {
            write("IDXT".toByteArray())
            starts.forEach { write(int16(it)) }
        }.toByteArray()
        val data = indxHeader(start = idxtStart, count = entries.size, cncxCount = 0) + body.toByteArray() + idxt
        val records = mutableListOf(main, data)
        if (labels.isNotEmpty()) {
            records += labels.fold(ByteArray(0)) { all, label ->
                val bytes = label.toByteArray()
                all + varint(bytes.size.toLong()) + bytes
            } + ByteArray(1)
        }
        return records
    }

    /** The CNCX offsets of [labels] as [index] writes them. */
    fun labelOffsets(labels: List<String>): List<Int> {
        var offset = 0
        return labels.map { label ->
            val bytes = label.toByteArray()
            offset.also { offset += varint(bytes.size.toLong()).size + bytes.size }
        }
    }

    private fun indxHeader(
        start: Int,
        count: Int,
        cncxCount: Int,
    ): ByteArray {
        val header = ByteArray(INDX_HEADER)
        "INDX".toByteArray().copyInto(header)
        header.put32(4, INDX_HEADER.toLong())
        header.put32(20, start.toLong())
        header.put32(24, count.toLong())
        header.put32(28, 65001)
        header.put32(52, cncxCount.toLong())
        return header
    }

    /** A forward variable-width number: seven bits a byte, high bit on the last. */
    fun varint(value: Long): ByteArray {
        val groups = mutableListOf<Int>()
        var rest = value
        do {
            groups.add(0, (rest and 0x7F).toInt())
            rest = rest shr 7
        } while (rest > 0)
        groups[groups.lastIndex] = groups.last() or 0x80
        return ByteArray(groups.size) { groups[it].toByte() }
    }

    fun values(vararg numbers: Long): ByteArray = numbers.fold(ByteArray(0)) { all, number -> all + varint(number) }

    fun int32(value: Long): ByteArray = ByteArray(4).also { it.put32(0, value) }

    private fun int16(value: Int): ByteArray = ByteArray(2).also { it.put16(0, value) }

    private fun ByteArray.put16(
        offset: Int,
        value: Int,
    ) {
        this[offset] = (value shr 8).toByte()
        this[offset + 1] = value.toByte()
    }

    private fun ByteArray.put32(
        offset: Int,
        value: Long,
    ) {
        for (index in 0..3) this[offset + index] = (value shr (24 - 8 * index)).toByte()
    }

    private const val PDB_HEADER = 78
    private const val INDX_HEADER = 192
}
