package com.example.unifiedreader.core.ebookconvert

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Fb2ToEpubTest {
    @Test
    fun readsTheTitleInfo() {
        val metadata = EbookConverter.readMetadata(ConvertibleBookKind.FB2, sampleFb2().toByteArray())

        assertEquals("The Test Book", metadata.title)
        assertEquals(listOf("Anna Maria Writer", "Pen"), metadata.authors)
        assertEquals("ru", metadata.language)
        assertEquals("Tales", metadata.series)
        assertEquals("2", metadata.seriesIndex)
        assertEquals("A short book.\n\nMade for tests.", metadata.description)
        assertEquals("image/png", metadata.cover?.mediaType)
    }

    @Test
    fun detectsPlainAndZippedFb2() {
        assertEquals(ConvertibleBookKind.FB2, EbookConverter.detectKind(sampleFb2().toByteArray()))
        assertEquals(ConvertibleBookKind.FB2, EbookConverter.detectKind(zipped("book.fb2", sampleFb2().toByteArray())))
        assertEquals(null, EbookConverter.detectKind("<html><body>no</body></html>".toByteArray()))
    }

    @Test
    fun makesAChapterPerSectionWithACoverPageAndNotes() {
        val epub = convert(sampleFb2().toByteArray())

        assertEquals("application/epub+zip", epub.getValue("mimetype"))
        val opf = epub.getValue("OEBPS/content.opf")
        assertTrue(opf.contains("<dc:title>The Test Book</dc:title>"))
        assertTrue(opf.contains("properties=\"cover-image\""))
        val spine = Regex("""<itemref idref="([^"]+)"""").findAll(opf).map { it.groupValues[1] }.toList()
        // The cover, the body's title page, the long first chapter, the short second one, the notes.
        assertEquals(
            listOf("id-cover_xhtml", "id-chapter0002_xhtml", "id-chapter0003_xhtml", "id-chapter0004_xhtml", "id-chapter0005_xhtml"),
            spine,
        )
        val first = epub.getValue("OEBPS/chapter0003.xhtml")
        assertTrue(first.contains("<h2 class=\"title\">Chapter One</h2>"))
        assertTrue(first.contains("<strong>bold</strong> and <em>italic</em>"))
        assertTrue(first.contains("<blockquote class=\"epigraph\">"))
        // The note link points into the notes file and is marked as a note reference.
        assertTrue(first.contains("<a epub:type=\"noteref\" class=\"noteref\" href=\"chapter0005.xhtml#n1\">1</a>"))
        // A link to the next chapter's section crosses files.
        assertTrue(first.contains("href=\"chapter0004.xhtml#second\""))
        val notes = epub.getValue("OEBPS/chapter0005.xhtml")
        assertTrue(notes.contains("<aside epub:type=\"footnote\" class=\"note\" id=\"n1\">"))
        val nav = epub.getValue("OEBPS/nav.xhtml")
        assertTrue(nav.contains(">Chapter One</a>"))
        assertTrue(nav.contains(">Chapter Two</a>"))
        assertTrue(nav.contains(">Part 2.1</a>"))
    }

    @Test
    fun readsWindows1251AndHtmlEntities() {
        val text = sampleFb2(title = "Война и мир", encoding = "windows-1251")
            .replace("Chapter One", "Глава&nbsp;первая")
        val epub = convert(text.toByteArray(charset("windows-1251")))

        assertTrue(epub.getValue("OEBPS/content.opf").contains("<dc:title>Война и мир</dc:title>"))
        assertTrue(epub.getValue("OEBPS/chapter0003.xhtml").contains("Глава\u00A0первая"))
    }

    @Test
    fun theSameBookMakesTheSameEpub() {
        val first = ByteArrayOutputStream().also {
            EbookConverter.convertToEpub(ConvertibleBookKind.FB2, sampleFb2().toByteArray(), it)
        }.toByteArray()
        val second = ByteArrayOutputStream().also {
            EbookConverter.convertToEpub(ConvertibleBookKind.FB2, sampleFb2().toByteArray(), it)
        }.toByteArray()

        assertArrayEquals(first, second)
    }

    @Test
    fun readsAZippedFb2() {
        val epub = convert(zipped("folder/book.fb2", sampleFb2().toByteArray()))

        assertNotNull(epub["OEBPS/chapter0003.xhtml"])
    }

    @Test(expected = EbookConversionException::class)
    fun refusesAFileThatIsNotFb2() {
        EbookConverter.convertToEpub(ConvertibleBookKind.FB2, "<html/>".toByteArray(), ByteArrayOutputStream())
    }

    private fun convert(bytes: ByteArray): Map<String, String> {
        val output = ByteArrayOutputStream()
        EbookConverter.convertToEpub(ConvertibleBookKind.FB2, bytes, output)
        return unzip(output.toByteArray())
    }
}

internal fun unzip(bytes: ByteArray): Map<String, String> = ZipInputStream(bytes.inputStream()).use { zip ->
    generateSequence { zip.nextEntry }.associate { entry -> entry.name to String(zip.readBytes(), Charsets.UTF_8) }
}

private fun zipped(
    name: String,
    bytes: ByteArray,
): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }
    return output.toByteArray()
}

// Enough paragraphs that the first chapter is a file of its own.
private val LongText = (1..60).joinToString("\n") { "<p>Paragraph $it of a chapter long enough to stand alone.</p>" }

// A 1x1 PNG.
private const val TinyPng = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="

private fun sampleFb2(
    title: String = "The Test Book",
    encoding: String = "UTF-8",
): String = """
    <?xml version="1.0" encoding="$encoding"?>
    <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
      <description>
        <title-info>
          <genre>prose</genre>
          <author><first-name>Anna</first-name><middle-name>Maria</middle-name><last-name>Writer</last-name></author>
          <author><nickname>Pen</nickname></author>
          <book-title>$title</book-title>
          <annotation><p>A short book.</p><p>Made for tests.</p></annotation>
          <coverpage><image l:href="#cover.png"/></coverpage>
          <lang>ru</lang>
          <sequence name="Tales" number="2"/>
        </title-info>
      </description>
      <body>
        <title><p>$title</p></title>
        <section id="first">
          <title><p>Chapter One</p></title>
          <epigraph><p>Words before.</p><text-author>Someone</text-author></epigraph>
          <p>Text with <strong>bold</strong> and <emphasis>italic</emphasis>.<a l:href="#n1" type="note">1</a></p>
          <p>See <a l:href="#second">the next chapter</a>.</p>
          $LongText
          <empty-line/>
          <image l:href="#cover.png"/>
        </section>
        <section id="second">
          <title><p>Chapter Two</p></title>
          <section><title><p>Part 2.1</p></title><p>Inside.</p></section>
          <poem><stanza><v>A line</v><v>Another line</v></stanza></poem>
        </section>
      </body>
      <body name="notes">
        <section id="n1"><title><p>1</p></title><p>The note.</p></section>
      </body>
      <binary id="cover.png" content-type="image/png">$TinyPng</binary>
    </FictionBook>
""".trimIndent()
