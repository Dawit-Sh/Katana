package com.example.unifiedreader.core.ebookconvert.mobi

import com.example.unifiedreader.core.ebookconvert.EbookConversionException
import com.example.unifiedreader.core.ebookconvert.EbookImage
import com.example.unifiedreader.core.ebookconvert.EbookMetadata
import com.example.unifiedreader.core.ebookconvert.sniffImageMediaType
import java.io.ByteArrayOutputStream
import org.jsoup.Jsoup

/**
 * A MOBI, AZW or AZW3 file. A combined file holds the book twice, as MOBI 6 for old Kindles and
 * as KF8; [kf8] is the KF8 part when there is one, the one to read.
 */
internal class MobiBook private constructor(
    val database: PalmDatabase,
    val mobi6: MobiHeader?,
    val kf8: MobiHeader?,
) {
    /** The header of the part that gets read: KF8 when there is one. */
    val main: MobiHeader = kf8 ?: mobi6 ?: error("A book has at least one part")

    /**
     * The first resource record. In a combined file the images live in the MOBI 6 part, and the
     * KF8 part's `kindle:embed` numbers count from there too.
     */
    val firstResourceRecord: Int = mobi6?.firstResourceRecord?.takeIf { it != NOT_SET } ?: main.firstResourceRecord

    /** The text of [header]'s part, decompressed. */
    fun text(header: MobiHeader = main): ByteArray {
        val decoder = textRecordDecoder(header, database)
        val output = ByteArrayOutputStream(header.textLength.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
        for (index in 1..header.textRecordCount) {
            val record = database.recordOrNull(header.start + index) ?: break
            output.write(decoder.decode(record.withoutTrailingEntries(header.extraDataFlags)))
        }
        return output.toByteArray()
    }

    /** Resource record [number], counted from one as `kindle:embed` and `recindex` count them. */
    fun resource(number: Int): ByteArray? {
        if (firstResourceRecord == NOT_SET || number < 1) return null
        return database.recordOrNull(firstResourceRecord + number - 1)
    }

    fun metadata(): EbookMetadata {
        val exth = main.exth
        val charset = main.charset
        return EbookMetadata(
            title = exth.string(Exth.UPDATED_TITLE, charset) ?: main.fullName ?: mobi6?.fullName,
            authors = exth.strings(Exth.AUTHOR, charset).flatMap(::splitAuthors).distinct(),
            description = exth.string(Exth.DESCRIPTION, charset)?.let { Jsoup.parse(it).text().trim() }?.takeIf(String::isNotEmpty),
            language = exth.string(Exth.LANGUAGE, charset) ?: localeLanguage(main.locale),
            publisher = exth.string(Exth.PUBLISHER, charset),
            cover = coverImage(),
            rightToLeft = main.exth.string(Exth.PAGE_PROGRESSION, Charsets.US_ASCII).equals("rtl", ignoreCase = true),
        )
    }

    /** The cover image the EXTH metadata names, as a resource number counted from one. */
    fun coverResourceNumber(): Int? = (main.exth.int(Exth.COVER_OFFSET) ?: mobi6?.exth?.int(Exth.COVER_OFFSET))
        ?.takeIf { it >= 0 && it != NOT_SET }
        ?.plus(1)

    private fun coverImage(): EbookImage? {
        val bytes = coverResourceNumber()?.let(::resource)
        val mediaType = bytes?.let(::sniffImageMediaType)
        return if (bytes != null && mediaType != null) EbookImage(mediaType, bytes) else null
    }

    companion object {
        fun open(bytes: ByteArray): MobiBook {
            refuseOtherKindleFormats(bytes)
            if (!looksLikeMobi(bytes)) {
                throw EbookConversionException(EbookConversionException.Reason.NotThisFormat, "Not a MOBI or Kindle book")
            }
            val database = PalmDatabase(bytes)
            val first = MobiHeader(database.record(0), start = 0)
            val boundary = first.exth.int(Exth.KF8_BOUNDARY)
            val kf8 = when {
                first.isKf8 -> first
                boundary != null && boundary > 0 -> database.recordOrNull(boundary)
                    ?.let { MobiHeader(it, start = boundary) }
                    ?.takeIf { it.isKf8 }
                else -> null
            }
            val book = MobiBook(database, mobi6 = first.takeUnless { it === kf8 }, kf8 = kf8)
            if (book.main.encryption != 0) {
                throw EbookConversionException(EbookConversionException.Reason.DrmProtected, "This Kindle book is locked (DRM)")
            }
            return book
        }

        private fun refuseOtherKindleFormats(bytes: ByteArray) {
            val head = bytes.ascii(0, minOf(bytes.size, KFX_SIGNATURE_LENGTH))
            when {
                head.startsWith("TPZ") -> throw EbookConversionException(
                    EbookConversionException.Reason.Unsupported,
                    "Topaz Kindle books can't be converted",
                )
                head.startsWith("CONT") || head.startsWith("êDRMION") -> throw EbookConversionException(
                    EbookConversionException.Reason.Unsupported,
                    "KFX Kindle books can't be converted",
                )
            }
        }

        private const val KFX_SIGNATURE_LENGTH = 8
    }
}

// "Last, First & Other" as Calibre writes several authors into one EXTH record.
private fun splitAuthors(value: String): List<String> =
    value.split(" & ", ";").map(String::trim).filter(String::isNotEmpty)

// The language a MOBI locale (a Windows language id) names, for the common ones.
private fun localeLanguage(locale: Int): String? = LocaleLanguages[locale and PRIMARY_LANGUAGE_MASK]

private const val PRIMARY_LANGUAGE_MASK = 0xFF

private val LocaleLanguages = mapOf(
    0x01 to "ar", 0x02 to "bg", 0x03 to "ca", 0x04 to "zh", 0x05 to "cs", 0x06 to "da", 0x07 to "de",
    0x08 to "el", 0x09 to "en", 0x0A to "es", 0x0B to "fi", 0x0C to "fr", 0x0D to "he", 0x0E to "hu",
    0x0F to "is", 0x10 to "it", 0x11 to "ja", 0x12 to "ko", 0x13 to "nl", 0x14 to "no", 0x15 to "pl",
    0x16 to "pt", 0x18 to "ro", 0x19 to "ru", 0x1A to "hr", 0x1B to "sk", 0x1D to "sv", 0x1F to "tr",
    0x22 to "uk", 0x2A to "vi", 0x5E to "am",
)
