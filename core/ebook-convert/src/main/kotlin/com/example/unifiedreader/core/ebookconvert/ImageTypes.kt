package com.example.unifiedreader.core.ebookconvert

/** The image type [bytes] start with, or null for anything else. */
internal fun sniffImageMediaType(bytes: ByteArray): String? = when {
    bytes.startsWith(JPEG_SIGNATURE) -> "image/jpeg"
    bytes.startsWith(PNG_SIGNATURE) -> "image/png"
    bytes.startsWith(GIF_SIGNATURE) -> "image/gif"
    bytes.startsWith(BMP_SIGNATURE) -> "image/bmp"
    bytes.startsWith(RIFF_SIGNATURE) && bytes.size > WEBP_TAG_END &&
        String(bytes, WEBP_TAG_START, WEBP_TAG_END - WEBP_TAG_START, Charsets.US_ASCII) == "WEBP" -> "image/webp"
    else -> null
}

/** The usual file extension for an image [mediaType]. */
internal fun imageExtension(mediaType: String): String = when (mediaType) {
    "image/jpeg" -> "jpg"
    "image/png" -> "png"
    "image/gif" -> "gif"
    "image/bmp" -> "bmp"
    "image/webp" -> "webp"
    "image/svg+xml" -> "svg"
    else -> "img"
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }

private fun bytesOf(vararg values: Int): ByteArray = ByteArray(values.size) { index -> values[index].toByte() }

private val JPEG_SIGNATURE = bytesOf(0xFF, 0xD8, 0xFF)
private val PNG_SIGNATURE = bytesOf(0x89, 0x50, 0x4E, 0x47)
private val GIF_SIGNATURE = "GIF8".toByteArray(Charsets.US_ASCII)
private val BMP_SIGNATURE = "BM".toByteArray(Charsets.US_ASCII)
private val RIFF_SIGNATURE = "RIFF".toByteArray(Charsets.US_ASCII)
private const val WEBP_TAG_START = 8
private const val WEBP_TAG_END = 12
