package com.example.unifiedreader.feature.reader.epub

import com.example.unifiedreader.core.ebookconvert.EbookConversionException
import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.feature.reader.core.ReaderOpenFailure
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvertedEpubFileTest {
    private val directory: File = Files.createTempDirectory("converted-epub").toFile()
    private val output = File(directory, "book.epub")

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun convertsAnFb2BookToAnEpub() {
        convertedEpubFile(fb2("One"), FormatType.FB2, output)

        ZipFile(output).use { zip ->
            assertEquals("application/epub+zip", zip.getInputStream(zip.getEntry("mimetype")).readBytes().decodeToString())
            assertTrue(zip.getEntry("OEBPS/content.opf") != null)
        }
    }

    @Test
    fun reusesTheEpubWhileTheBookIsTheSame() {
        convertedEpubFile(fb2("One"), FormatType.FB2, output)
        output.writeText("kept")

        convertedEpubFile(fb2("One"), FormatType.FB2, output)

        assertEquals("kept", output.readText())
    }

    @Test
    fun makesTheEpubAgainWhenTheBookChanges() {
        convertedEpubFile(fb2("One"), FormatType.FB2, output)
        val first = output.readBytes()

        convertedEpubFile(fb2("Two"), FormatType.FB2, output)

        assertNotEquals(first.toList(), output.readBytes().toList())
    }

    @Test
    fun usesAnEpubFiledUnderAnotherFormatAsItIs() {
        convertedEpubFile(fb2("One"), FormatType.FB2, output)
        val epub = output.readBytes()
        val elsewhere = File(directory, "other.epub")

        convertedEpubFile(epub, FormatType.MOBI, elsewhere)

        assertArrayEquals(epub, elsewhere.readBytes())
    }

    @Test
    fun saysALockedOrNewerKindleBookCantOpen() {
        assertEquals(
            ReaderOpenFailure.DrmLocked,
            EbookConversionException(EbookConversionException.Reason.DrmProtected, "locked").toReaderOpenFailure(),
        )
        assertEquals(
            ReaderOpenFailure.KindleFormatUnsupported,
            EbookConversionException(EbookConversionException.Reason.Unsupported, "KFX").toReaderOpenFailure(),
        )
        assertEquals(
            ReaderOpenFailure.NotReadable,
            EbookConversionException(EbookConversionException.Reason.Damaged, "broken").toReaderOpenFailure(),
        )
    }

    @Test(expected = EbookConversionException::class)
    fun aFileThatIsNoBookFailsAndLeavesNothing() {
        try {
            convertedEpubFile("not a book".toByteArray(), FormatType.MOBI, output)
        } finally {
            assertTrue(!output.exists())
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }
    }

    private fun fb2(title: String): ByteArray = """
        <?xml version="1.0" encoding="UTF-8"?>
        <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
          <description><title-info><book-title>$title</book-title><lang>en</lang></title-info></description>
          <body><section><title><p>Chapter</p></title><p>Text of $title.</p></section></body>
        </FictionBook>
    """.trimIndent().toByteArray()
}
