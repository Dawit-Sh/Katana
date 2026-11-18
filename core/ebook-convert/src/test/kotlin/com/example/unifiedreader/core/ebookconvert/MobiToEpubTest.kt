package com.example.unifiedreader.core.ebookconvert

import com.example.unifiedreader.core.ebookconvert.MobiTestBooks.Record0
import com.example.unifiedreader.core.ebookconvert.MobiTestBooks.TinyPng
import com.example.unifiedreader.core.ebookconvert.MobiTestBooks.exthInt
import com.example.unifiedreader.core.ebookconvert.MobiTestBooks.index
import com.example.unifiedreader.core.ebookconvert.MobiTestBooks.int32
import com.example.unifiedreader.core.ebookconvert.MobiTestBooks.labelOffsets
import com.example.unifiedreader.core.ebookconvert.MobiTestBooks.palmDatabase
import com.example.unifiedreader.core.ebookconvert.MobiTestBooks.record0
import com.example.unifiedreader.core.ebookconvert.MobiTestBooks.textRecords
import com.example.unifiedreader.core.ebookconvert.MobiTestBooks.values
import com.example.unifiedreader.core.ebookconvert.mobi.decompressPalmDoc
import com.example.unifiedreader.core.ebookconvert.mobi.withoutTrailingEntries
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MobiToEpubTest {
    @Test
    fun decompressesPalmDoc() {
        // "ab", a back reference (distance 2, length 3), a space pair (" A"), a run of two literals.
        val compressed = "ab".toByteArray() + byteArrayOf(0x80.toByte(), 0x10, 0xC1.toByte(), 2) + "xy".toByteArray()

        assertEquals("ababa Axy", String(decompressPalmDoc(compressed)))
    }

    @Test
    fun dropsTrailingEntriesAndMultibyteBytes() {
        val record = "text".toByteArray() + byteArrayOf(0x00) + byteArrayOf(0x11, 0x22, 0x83.toByte())

        assertArrayEquals("text".toByteArray(), record.withoutTrailingEntries(extraDataFlags = 0b11))
    }

    @Test
    fun readsMobi6Metadata() {
        val metadata = EbookConverter.readMetadata(ConvertibleBookKind.MOBI, mobi6Book())

        assertEquals("Frankenstein", metadata.title)
        assertEquals(listOf("Mary Shelley"), metadata.authors)
        assertEquals("en", metadata.language)
        assertEquals("image/png", metadata.cover?.mediaType)
        assertEquals(ConvertibleBookKind.MOBI, EbookConverter.detectKind(mobi6Book()))
    }

    @Test
    fun splitsMobi6AtPageBreaksWithLinksImagesAndContents() {
        val epub = convert(mobi6Book())

        val first = epub.getValue("OEBPS/part0000.xhtml")
        val second = epub.getValue("OEBPS/part0001.xhtml")
        assertTrue(first.contains("<h1>Chapter 1</h1>"))
        assertTrue(first.contains("<img src=\"image00001.png\" />"))
        // MOBI 6 spacing attributes become CSS.
        assertTrue(first, first.contains("<p style=\"text-indent: 0pt; margin-top: 1em\">Go "))
        // filepos links point at an anchor in the file that holds the position.
        val target = Regex("""href="part0001\.xhtml#(filepos\d+)"""").find(first)?.groupValues?.get(1)
        assertTrue("link: $first", target != null && second.contains("id=\"$target\""))
        assertTrue(second.contains("<h1>Chapter 2</h1>"))
        val opf = epub.getValue("OEBPS/content.opf")
        assertTrue(opf.contains("<dc:title>Frankenstein</dc:title>"))
        assertTrue(opf.contains("href=\"image00001.png\" media-type=\"image/png\" properties=\"cover-image\""))
        val nav = epub.getValue("OEBPS/nav.xhtml")
        assertTrue(nav.contains("href=\"part0000.xhtml\">Chapter 1</a>"))
        assertTrue(nav.contains("href=\"part0001.xhtml\">Chapter 2</a>"))
    }

    @Test(expected = EbookConversionException::class)
    fun refusesALockedBook() {
        convert(mobi6Book(encryption = 2))
    }

    @Test
    fun saysWhyALockedBookFails() {
        val error = runCatching { convert(mobi6Book(encryption = 2)) }.exceptionOrNull() as EbookConversionException

        assertEquals(EbookConversionException.Reason.DrmProtected, error.reason)
    }

    @Test
    fun rebuildsKf8FilesFromSkeletonsAndFragments() {
        val epub = convert(kf8Book())

        val first = epub.getValue("OEBPS/part0000.xhtml")
        val second = epub.getValue("OEBPS/part0001.xhtml")
        assertTrue(first, first.contains("<h1 id=\"start\">One</h1>"))
        assertTrue(first, first.contains("<link href=\"flow0001.css\" rel=\"stylesheet\" type=\"text/css\" />"))
        assertTrue(first, first.contains("<img src=\"image00001.png\" />"))
        // The kindle:pos link lands on the fragment's div, which gets the id the link needs.
        assertTrue(first, first.contains("href=\"part0001.xhtml#aid-3\""))
        assertTrue(second, second.contains("<div id=\"aid-3\"><h1>Two</h1>"))
        assertTrue(!first.contains("aid=\""))
        assertEquals("h1 { color: red; }", epub.getValue("OEBPS/flow0001.css"))
        val nav = epub.getValue("OEBPS/nav.xhtml")
        assertTrue(nav, nav.contains("href=\"part0000.xhtml#start\">One</a>"))
        assertTrue(nav, nav.contains("href=\"part0001.xhtml#aid-3\">Two</a>"))
    }

    @Test
    fun theSameMobiMakesTheSameEpub() {
        assertArrayEquals(convertBytes(kf8Book()), convertBytes(kf8Book()))
    }

    private fun convert(book: ByteArray): Map<String, String> = unzip(convertBytes(book))

    private fun convertBytes(book: ByteArray): ByteArray =
        ByteArrayOutputStream().also { EbookConverter.convertToEpub(ConvertibleBookKind.MOBI, book, it) }.toByteArray()

    private fun mobi6Book(encryption: Int = 0): ByteArray {
        val filler = (1..30).joinToString("") { "<p>A paragraph of words to make the chapter long enough.</p>" }
        val head = "<html><head><guide></guide></head><body><h1>Chapter 1</h1>" +
            "<p width=\"0pt\" height=\"1em\">Go <a filepos=XXXXXXXXXX>on</a>.</p>" +
            "<img recindex=\"00001\">$filler<mbp:pagebreak/>"
        val target = head.length
        val html = head.replace("XXXXXXXXXX", "%010d".format(target)) + "<h1>Chapter 2</h1>$filler</body></html>"
        val text = html.toByteArray()
        val trailer = byteArrayOf(0x00, 0x11, 0x22, 0x83.toByte())
        val records = textRecords(text, trailer)
        val zero = record0(
            Record0(
                textLength = text.size,
                textRecordCount = records.size,
                encryption = encryption,
                extraFlags = 0b11,
                firstImage = records.size + 1,
                exth = listOf(
                    100 to "Mary Shelley".toByteArray(),
                    503 to "Frankenstein".toByteArray(),
                    524 to "en".toByteArray(),
                    201 to exthInt(0),
                ),
            ),
        )
        return palmDatabase(listOf(zero) + records + listOf(TinyPng))
    }

    // Two files: skeleton + one fragment each, a stylesheet flow, an image, and a two-entry NCX.
    private fun kf8Book(): ByteArray {
        val text = Kf8Sample.TEXT.toByteArray()
        val records = textRecords(text)
        val skeletons = Kf8Sample.skeletonIndex()
        val fragments = Kf8Sample.fragmentIndex()
        val ncx = Kf8Sample.ncxIndex()
        val flowText = Kf8Sample.FLOW_TEXT.length.toLong()
        val fdst = "FDST".toByteArray() + int32(12) + int32(2) + int32(0) + int32(flowText) +
            int32(flowText) + int32(text.size.toLong())
        val firstImage = 1 + records.size
        val skeletonRecord = firstImage + 1
        val fragmentRecord = skeletonRecord + skeletons.size
        val ncxRecord = fragmentRecord + fragments.size
        val zero = record0(
            Record0(
                textLength = text.size,
                textRecordCount = records.size,
                version = 8,
                firstImage = firstImage,
                exth = listOf(503 to "Kindle Test".toByteArray()),
                fields = mapOf(0xC0 to ncxRecord + ncx.size, 0xF4 to ncxRecord, 0xF8 to fragmentRecord, 0xFC to skeletonRecord),
            ),
        )
        return palmDatabase(listOf(zero) + records + listOf(TinyPng) + skeletons + fragments + ncx + listOf(fdst))
    }
}

/** The text and indexes of a small KF8 book: two XHTML files and a stylesheet flow. */
private object Kf8Sample {
    private const val SKELETON_0 = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><head>" +
        "<link href=\"kindle:flow:0001?mime=text/css\" rel=\"stylesheet\" type=\"text/css\"/></head><body aid=\"0\"></body></html>"
    private const val FRAGMENT_0 = "<div aid=\"1\"><h1 id=\"start\">One</h1><p>To <a href=\"kindle:pos:fid:0001:off:0000000000\">two</a>." +
        "<img src=\"kindle:embed:0001?mime=image/png\"/></p></div>"
    private const val SKELETON_1 = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><head></head>" +
        "<body aid=\"2\"></body></html>"
    private const val FRAGMENT_1 = "<div aid=\"3\"><h1>Two</h1><p>Text.</p></div>"
    private const val CSS = "h1 { color: red; }"
    private val selectors = listOf("P-//*[@aid='0']", "P-//*[@aid='2']")
    private val tocLabels = listOf("One", "Two")
    private val skeleton1Start = SKELETON_0.length + FRAGMENT_0.length

    const val FLOW_TEXT = SKELETON_0 + FRAGMENT_0 + SKELETON_1 + FRAGMENT_1
    const val TEXT = FLOW_TEXT + CSS

    fun skeletonIndex(): List<ByteArray> = index(
        tagx = listOf(intArrayOf(1, 1, 0x03, 0), intArrayOf(6, 2, 0x0C, 0), intArrayOf(0, 0, 0, 1)),
        controlByteCount = 1,
        entries = listOf(
            "SKEL0000000000" to (byteArrayOf(0x05) + values(1, 0, SKELETON_0.length.toLong())),
            "SKEL0000000001" to (byteArrayOf(0x05) + values(1, skeleton1Start.toLong(), SKELETON_1.length.toLong())),
        ),
    )

    fun fragmentIndex(): List<ByteArray> {
        val offsets = labelOffsets(selectors)
        val insert0 = SKELETON_0.indexOf("</body>")
        val insert1 = skeleton1Start + SKELETON_1.indexOf("</body>")
        val tags = listOf(2 to 0x01, 3 to 0x02, 4 to 0x04, 6 to 0x08).map { (tag, mask) ->
            intArrayOf(tag, if (tag == 6) 2 else 1, mask, 0)
        } + listOf(intArrayOf(0, 0, 0, 1))
        return index(
            tagx = tags,
            controlByteCount = 1,
            entries = listOf(
                "%010d".format(insert0) to (byteArrayOf(0x0F) + values(offsets[0].toLong(), 0, 0, 0, FRAGMENT_0.length.toLong())),
                "%010d".format(insert1) to (byteArrayOf(0x0F) + values(offsets[1].toLong(), 1, 1, 0, FRAGMENT_1.length.toLong())),
            ),
            labels = selectors,
        )
    }

    fun ncxIndex(): List<ByteArray> {
        val offsets = labelOffsets(tocLabels)
        val startOffset = FRAGMENT_0.indexOf("<h1").toLong()
        return index(
            tagx = listOf(intArrayOf(3, 1, 0x01, 0), intArrayOf(4, 1, 0x02, 0), intArrayOf(6, 2, 0x04, 0), intArrayOf(0, 0, 0, 1)),
            controlByteCount = 1,
            entries = listOf(
                "0" to (byteArrayOf(0x07) + values(offsets[0].toLong(), 0, 0, startOffset + 1)),
                "1" to (byteArrayOf(0x07) + values(offsets[1].toLong(), 0, 1, 0)),
            ),
            labels = tocLabels,
        )
    }
}
