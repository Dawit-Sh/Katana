package com.example.unifiedreader.core.ebookconvert.mobi

import com.example.unifiedreader.core.ebookconvert.EbookConversionException
import java.io.ByteArrayOutputStream

/**
 * HUFF/CDIC, the Huffman compression bigger Kindle books use: a canonical Huffman code (the HUFF
 * record) over a dictionary of phrases (the CDIC records), where a phrase may itself be compressed.
 */
internal class HuffCdicDecoder(
    huff: ByteArray,
    cdics: List<ByteArray>,
) {
    private val cacheLength = IntArray(CACHE_SIZE)
    private val cacheTerminal = BooleanArray(CACHE_SIZE)
    private val cacheMaxCode = LongArray(CACHE_SIZE)
    private val minCode = LongArray(MAX_CODE_LENGTH + 1)
    private val maxCode = LongArray(MAX_CODE_LENGTH + 1)
    private val phrases = mutableListOf<ByteArray>()
    private val phraseIsLiteral = mutableListOf<Boolean>()

    init {
        if (huff.ascii(0, MAGIC_LENGTH) != "HUFF") {
            throw EbookConversionException(EbookConversionException.Reason.Damaged, "The book's HUFF record is missing")
        }
        readCodeTables(huff)
        cdics.forEach(::readDictionary)
    }

    private fun readCodeTables(huff: ByteArray) {
        val cacheOffset = huff.u32(CACHE_OFFSET_FIELD).toInt()
        val baseOffset = huff.u32(BASE_OFFSET_FIELD).toInt()
        for (index in 0 until CACHE_SIZE) {
            val value = huff.u32(cacheOffset + index * WORD)
            val length = (value and CODE_LENGTH_MASK).toInt()
            cacheLength[index] = length
            cacheTerminal[index] = value and TERMINAL_FLAG != 0L
            cacheMaxCode[index] = (((value ushr BYTE_BITS) + 1) shl (WORD_BITS - length)) - 1
        }
        for (length in 1..MAX_CODE_LENGTH) {
            val pairOffset = baseOffset + (length - 1) * 2 * WORD
            minCode[length] = huff.u32(pairOffset) shl (WORD_BITS - length)
            maxCode[length] = ((huff.u32(pairOffset + WORD) + 1) shl (WORD_BITS - length)) - 1
        }
    }

    private fun readDictionary(cdic: ByteArray) {
        if (cdic.ascii(0, MAGIC_LENGTH) != "CDIC") return
        val phraseCount = cdic.u32(CDIC_PHRASES_FIELD).toInt()
        val bits = cdic.u32(CDIC_BITS_FIELD).toInt()
        val count = minOf(1 shl bits, phraseCount - phrases.size)
        for (index in 0 until count) {
            val offset = cdic.u16(CDIC_HEADER_LENGTH + index * 2)
            val lengthField = cdic.u16(CDIC_HEADER_LENGTH + offset)
            val start = CDIC_HEADER_LENGTH + offset + 2
            phrases += cdic.copyOfRange(start, start + (lengthField and PHRASE_LENGTH_MASK))
            phraseIsLiteral += lengthField and PHRASE_LITERAL_FLAG != 0
        }
    }

    fun decode(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(data.size * 2)
        decodeInto(data, output, depth = 0)
        return output.toByteArray()
    }

    private fun decodeInto(
        data: ByteArray,
        output: ByteArrayOutputStream,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) throw EbookConversionException(EbookConversionException.Reason.Damaged, "Looping HUFF/CDIC phrases")
        val padded = data.copyOf(data.size + WINDOW_BYTES)
        var bitsLeft = data.size.toLong() * BYTE_BITS
        var position = 0
        var window = padded.u64(position)
        var shift = WORD_BITS
        while (true) {
            if (shift <= 0) {
                position += WORD
                window = padded.u64(position)
                shift += WORD_BITS
            }
            val code = (window ushr shift) and WORD_MASK
            val cacheIndex = (code ushr CACHE_INDEX_SHIFT).toInt()
            var length = cacheLength[cacheIndex]
            var max = cacheMaxCode[cacheIndex]
            if (!cacheTerminal[cacheIndex]) {
                while (length < MAX_CODE_LENGTH && code < minCode[length]) length += 1
                max = maxCode[length]
            }
            shift -= length
            bitsLeft -= length
            if (bitsLeft < 0 || length == 0) break
            output.write(phrase(((max - code) ushr (WORD_BITS - length)).toInt(), depth))
        }
    }

    // A dictionary phrase, decoded the first time it's needed if it is itself compressed.
    private fun phrase(
        index: Int,
        depth: Int,
    ): ByteArray {
        if (index !in phrases.indices) throw EbookConversionException(EbookConversionException.Reason.Damaged, "No phrase $index")
        if (!phraseIsLiteral[index]) {
            val decoded = ByteArrayOutputStream()
            phraseIsLiteral[index] = true
            decodeInto(phrases[index], decoded, depth + 1)
            phrases[index] = decoded.toByteArray()
        }
        return phrases[index]
    }

    private fun ByteArray.u64(offset: Int): Long = (u32(offset) shl WORD_BITS) or u32(offset + WORD)

    private companion object {
        const val MAGIC_LENGTH = 4
        const val CACHE_OFFSET_FIELD = 8
        const val BASE_OFFSET_FIELD = 12
        const val CACHE_SIZE = 256
        const val MAX_CODE_LENGTH = 32
        const val WORD = 4
        const val WORD_BITS = 32
        const val WORD_MASK = 0xFFFF_FFFFL
        const val BYTE_BITS = 8
        const val WINDOW_BYTES = 8
        const val CACHE_INDEX_SHIFT = 24
        const val CODE_LENGTH_MASK = 0x1FL
        const val TERMINAL_FLAG = 0x80L
        const val CDIC_PHRASES_FIELD = 8
        const val CDIC_BITS_FIELD = 12
        const val CDIC_HEADER_LENGTH = 16
        const val PHRASE_LENGTH_MASK = 0x7FFF
        const val PHRASE_LITERAL_FLAG = 0x8000
        const val MAX_DEPTH = 32
    }
}
