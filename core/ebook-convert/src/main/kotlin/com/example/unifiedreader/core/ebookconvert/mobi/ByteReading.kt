package com.example.unifiedreader.core.ebookconvert.mobi

/** Big-endian reads, as everything in a Palm database is. */
internal fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and BYTE_MASK

internal fun ByteArray.u16(offset: Int): Int = (u8(offset) shl BITS_PER_BYTE) or u8(offset + 1)

internal fun ByteArray.u32(offset: Int): Long =
    (u16(offset).toLong() shl BITS_PER_SHORT) or u16(offset + 2).toLong()

internal fun ByteArray.ascii(
    offset: Int,
    length: Int,
): String = if (offset < 0 || offset + length > size) "" else String(this, offset, length, Charsets.ISO_8859_1)

/**
 * A forward-encoded variable-width number, as MOBI indexes store them: seven bits a byte, the last
 * byte marked by its high bit. Returns the number and how many bytes it took.
 */
internal fun ByteArray.forwardVarint(offset: Int): Pair<Long, Int> {
    var value = 0L
    var consumed = 0
    while (offset + consumed < size) {
        val byte = u8(offset + consumed)
        consumed += 1
        value = (value shl VARINT_BITS) or (byte and VARINT_VALUE_MASK).toLong()
        if (byte and VARINT_END_BIT != 0) break
    }
    return value to consumed
}

/** A Kindle base-32 number (digits 0-9 and A-V), as `kindle:` links write record and position numbers. */
internal fun String.fromKindleBase32(): Long = fold(0L) { value, char ->
    val digit = KINDLE_BASE32_DIGITS.indexOf(char.uppercaseChar())
    require(digit >= 0) { "Not a Kindle base-32 digit: $char" }
    value * KINDLE_BASE32_RADIX + digit
}

internal const val NOT_SET = -1
internal const val BYTE_MASK = 0xFF
private const val BITS_PER_BYTE = 8
private const val BITS_PER_SHORT = 16
private const val VARINT_BITS = 7
private const val VARINT_VALUE_MASK = 0x7F
private const val VARINT_END_BIT = 0x80
private const val KINDLE_BASE32_DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUV"
private const val KINDLE_BASE32_RADIX = 32
