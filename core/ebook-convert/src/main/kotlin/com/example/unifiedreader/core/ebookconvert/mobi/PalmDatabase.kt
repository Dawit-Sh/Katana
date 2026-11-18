package com.example.unifiedreader.core.ebookconvert.mobi

import com.example.unifiedreader.core.ebookconvert.EbookConversionException

/**
 * A Palm database (PDB), the container MOBI, AZW and AZW3 files share: a header and a list of
 * records. Record contents are read on demand.
 */
internal class PalmDatabase(private val bytes: ByteArray) {
    val type: String = bytes.ascii(TYPE_OFFSET, TYPE_LENGTH)
    val recordCount: Int = bytes.u16(RECORD_COUNT_OFFSET)
    private val offsets = IntArray(recordCount) { index ->
        bytes.u32(RECORD_LIST_OFFSET + index * RECORD_ENTRY_SIZE).toInt()
    }

    init {
        if (recordCount == 0 || offsets.any { it < 0 || it > bytes.size }) {
            throw EbookConversionException(EbookConversionException.Reason.Damaged, "The book's record list is broken")
        }
    }

    fun record(index: Int): ByteArray {
        if (index !in 0 until recordCount) {
            throw EbookConversionException(EbookConversionException.Reason.Damaged, "The book has no record $index")
        }
        val start = offsets[index]
        val end = if (index + 1 < recordCount) offsets[index + 1] else bytes.size
        return bytes.copyOfRange(start, end.coerceAtLeast(start))
    }

    /** Record [index], or null when the book has no such record. */
    fun recordOrNull(index: Int): ByteArray? = if (index in 0 until recordCount) record(index) else null

    companion object {
        const val HEADER_LENGTH = 78
        const val TYPE_OFFSET = 60
        const val TYPE_LENGTH = 8
        private const val RECORD_COUNT_OFFSET = 76
        private const val RECORD_LIST_OFFSET = 78
        private const val RECORD_ENTRY_SIZE = 8
    }
}

/** Whether [bytes] are a Mobipocket or Kindle book (MOBI, AZW, AZW3, PRC). */
internal fun looksLikeMobi(bytes: ByteArray): Boolean =
    bytes.size > PalmDatabase.HEADER_LENGTH &&
        bytes.ascii(PalmDatabase.TYPE_OFFSET, PalmDatabase.TYPE_LENGTH) in MOBI_DATABASE_TYPES

internal val MOBI_DATABASE_TYPES = setOf("BOOKMOBI", "TEXtREAd")
