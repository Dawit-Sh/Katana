package com.example.unifiedreader

import android.content.Context
import android.net.Uri
import com.example.unifiedreader.core.ebookconvert.ConvertibleBookKind
import com.example.unifiedreader.core.ebookconvert.EbookConverter
import com.example.unifiedreader.core.ebookconvert.EbookMetadata
import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.core.model.isConvertedToEpub
import com.example.unifiedreader.core.model.readableFormatOfFileName

/** A MOBI, AZW, AZW3, FB2 or DjVu file found on import: its format and what it says about itself. */
internal data class LocalBookFileProbe(
    val format: FormatType,
    val metadata: EbookMetadata?,
)

/**
 * Whether [uri] is a MOBI, AZW, AZW3, FB2 or DjVu book, from its name or else its first bytes,
 * with its title, authors and description (DjVu files have none Katana reads).
 */
internal fun probeLocalBookFile(
    context: Context,
    uri: Uri,
    displayName: String,
): LocalBookFileProbe? {
    val named = displayName.readableFormatOfFileName()?.takeIf { it.isConvertedToEpub() || it == FormatType.DJVU }
    val head = context.contentResolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(PROBE_HEAD_BYTES)
        val read = input.read(buffer)
        if (read <= 0) ByteArray(0) else buffer.copyOf(read)
    }
    return when {
        head == null -> null
        head.isDjvu() -> LocalBookFileProbe(FormatType.DJVU, metadata = null)
        // Read whole only when its name or first bytes say MOBI or FB2: a big comic archive isn't.
        named == null && EbookConverter.detectKind(head) == null -> null
        else -> convertedBookProbe(context, uri, named)
    }
}

private fun convertedBookProbe(
    context: Context,
    uri: Uri,
    named: FormatType?,
): LocalBookFileProbe? {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    val kind = bytes?.let(EbookConverter::detectKind) ?: return null
    val format = when {
        kind == ConvertibleBookKind.FB2 -> FormatType.FB2
        named == FormatType.AZW || named == FormatType.AZW3 -> named
        else -> FormatType.MOBI
    }
    return LocalBookFileProbe(format, runCatching { EbookConverter.readMetadata(kind, bytes) }.getOrNull())
}

/** The metadata of the MOBI, AZW, AZW3 or FB2 book at [uri], or null when it can't be read. */
internal fun readConvertedBookMetadata(
    context: Context,
    uri: Uri,
): EbookMetadata? = runCatching {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val kind = EbookConverter.detectKind(bytes) ?: return null
    EbookConverter.readMetadata(kind, bytes)
}.getOrNull()

internal fun coverExtension(mediaType: String): String = when (mediaType) {
    "image/png" -> "png"
    "image/gif" -> "gif"
    "image/webp" -> "webp"
    "image/bmp" -> "bmp"
    else -> "jpg"
}

// DjVu files start "AT&TFORM", then a size, then DJVU (one page), DJVM (several) or DJVI.
private fun ByteArray.isDjvu(): Boolean =
    size >= DJVU_SIGNATURE_LENGTH &&
        String(this, 0, DJVU_FORM_LENGTH, Charsets.US_ASCII) == "AT&TFORM" &&
        String(this, DJVU_KIND_OFFSET, DJVU_KIND_LENGTH, Charsets.US_ASCII) in DjvuKinds

private val DjvuKinds = setOf("DJVU", "DJVM", "DJVI")
private const val PROBE_HEAD_BYTES = 4096
private const val DJVU_FORM_LENGTH = 8
private const val DJVU_KIND_OFFSET = 12
private const val DJVU_KIND_LENGTH = 4
private const val DJVU_SIGNATURE_LENGTH = 16
