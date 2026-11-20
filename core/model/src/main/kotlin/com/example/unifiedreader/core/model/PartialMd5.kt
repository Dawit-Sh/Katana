package com.example.unifiedreader.core.model

import java.security.MessageDigest

/**
 * KOReader's "partial MD5" of a file, which Grimmory keeps as each file's fingerprint: the MD5 of up to
 * 1 KB read at each of twelve places, 0, 1 KB, 4 KB, 16 KB and so on by fours up to 1 GB, stopping at the
 * file's end. Only these 12 KB are ever read, so a book on a server can be fingerprinted with a few small
 * range requests instead of a download.
 */
object PartialMd5 {
    const val SAMPLE_BYTES: Int = 1024

    /** The byte ranges, start inclusive and end exclusive, a file of [fileSize] bytes is fingerprinted from. */
    fun ranges(fileSize: Long): List<LongRange> = SAMPLE_STARTS
        .takeWhile { start -> start < fileSize }
        .map { start -> start until minOf(start + SAMPLE_BYTES, fileSize) }

    /** The fingerprint (32 lowercase hex digits) of the samples read at [ranges], in their order. */
    fun of(samples: List<ByteArray>): String {
        val digest = MessageDigest.getInstance("MD5")
        samples.forEach(digest::update)
        return digest.digest().toLowerHex()
    }

    /** The fingerprint of a file of [fileSize] bytes, read through [read] (start, byte count) one sample at a time. */
    inline fun of(fileSize: Long, read: (start: Long, length: Int) -> ByteArray): String =
        of(ranges(fileSize).map { range -> read(range.first, (range.last - range.first + 1).toInt()) })

    // KOReader's 1024 << 2i for i from -1 to 10; i = -1 shifts out to 0 there (Lua, Java and JavaScript alike).
    private val SAMPLE_STARTS: List<Long> = listOf(0L) + (0..10).map { step -> 1024L shl (2 * step) }
}
