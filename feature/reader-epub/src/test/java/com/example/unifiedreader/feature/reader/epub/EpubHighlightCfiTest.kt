package com.example.unifiedreader.feature.reader.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Highlight places both ways: words in a chapter and the EPUB CFI Grimmory's web reader
 * (foliate-js) and the grimmory.koplugin keep for them. The expected CFIs are worked out by hand
 * from foliate-js's epubcfi.js rules (elements even, the text between them odd, ids asserted, a
 * range's shared steps first).
 */
class EpubHighlightCfiTest {
    private val chapter = checkNotNull(
        EpubChapterText.parse(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE html>
            <html xmlns="http://www.w3.org/1999/xhtml">
            <head><title>One</title></head>
            <body>
            <section id="ch1">
            <h1>One</h1>
            <p>It was the first summer&nbsp;of <em>the</em> hole.</p>
            <p>She <!-- a note -->walked to the <![CDATA[river]]> and back.</p>
            </section>
            </body>
            </html>
            """.trimIndent().toByteArray(),
        ),
    )
    private val chapterEight = listOf(CfiStep(6), CfiStep(8))

    private fun cfiOf(words: String, occurrence: Int = 0): String {
        var start = -1
        repeat(occurrence + 1) { start = chapter.text.indexOf(words, start + 1) }
        val (from, to) = checkNotNull(chapter.cfiPaths(EpubTextSpan(start, start + words.length)))
        return EpubCfi.formatRange(chapterEight, from, to)
    }

    private fun wordsAt(cfi: String): String? =
        EpubCfi.parse(cfi)?.let(chapter::spanOf)?.let { span -> chapter.text.substring(span.start, span.end) }

    @Test
    fun `the body's text is what a browser's textContent is, entities and CDATA read, comments left out`() {
        assertTrue(chapter.text.contains("summer of the hole."))
        assertTrue(chapter.text.contains("She walked to the river and back."))
    }

    @Test
    fun `words in one run of text share every step but the offsets`() {
        assertEquals("epubcfi(/6/8!/4/2[ch1]/4,/1:11,/1:23)", cfiOf("first summer"))
    }

    @Test
    fun `words across an element end in the text after it`() {
        assertEquals("epubcfi(/6/8!/4/2[ch1]/4,/1:24,/3:5)", cfiOf("of the hole"))
    }

    // A comment splits the text in the file, not in the CFI's count; CDATA is text.
    @Test
    fun `text around a comment and CDATA counts as one piece`() {
        assertEquals("epubcfi(/6/8!/4/2[ch1]/6,/1:18,/1:27)", cfiOf("river and"))
    }

    @Test
    fun `words across paragraphs share only their common elements`() {
        val start = chapter.text.indexOf("hole.")
        val end = chapter.text.indexOf("She walked") + "She walked".length
        val (from, to) = checkNotNull(chapter.cfiPaths(EpubTextSpan(start, end)))

        assertEquals("epubcfi(/6/8!/4/2[ch1],/4/3:1,/6/1:10)", EpubCfi.formatRange(chapterEight, from, to))
    }

    @Test
    fun `a CFI comes back to the same words`() {
        listOf("first summer", "of the hole", "river and", "One", "and back.").forEach { words ->
            assertEquals(words, wordsAt(cfiOf(words)))
        }
        assertEquals("hole.\nShe walked", wordsAt("epubcfi(/6/8!/4/2[ch1],/4/3:1,/6/1:10)"))
    }

    // The grimmory.koplugin writes CFIs without ids, and its parent may stop earlier.
    @Test
    fun `KOReader's CFIs without ids find the same words`() {
        assertEquals("first summer", wordsAt("epubcfi(/6/8!/4/2/4,/1:11,/1:23)"))
        assertEquals("first summer", wordsAt("epubcfi(/6/8!/4/2,/4/1:11,/4/1:23)"))
    }

    @Test
    fun `spaces at a CFI's ends are left out of the words`() {
        assertEquals("of", wordsAt("epubcfi(/6/8!/4/2[ch1]/4,/1:23,/1:27)"))
    }

    @Test
    fun `a CFI pointing outside the body or past the elements finds nothing`() {
        assertNull(wordsAt("epubcfi(/6/8!/2/2,/1:0,/1:3)"))
        assertNull(wordsAt("epubcfi(/6/8!/4/2[ch1]/40/2,/1:0,/1:3)"))
    }

    @Test
    fun `a selection's quote is found though its line breaks and spaces differ from the document's`() {
        // As Readium quotes a selection: the words as selected, the body's text before and after.
        val span = checkNotNull(chapter.find(highlight = "hole.\n\nShe walked", before = "summer of the ", after = " to the river"))

        assertEquals("hole.\nShe walked", chapter.text.substring(span.start, span.end))
    }

    @Test
    fun `of words that come twice, the one whose surroundings match is found`() {
        val second = checkNotNull(chapter.find(highlight = "One", before = "", after = "\nIt was"))
        val first = checkNotNull(chapter.find(highlight = "the", before = "It was ", after = " first"))

        assertEquals(chapter.text.indexOf("One"), second.start)
        assertEquals(chapter.text.indexOf("the first"), first.start)
        val later = checkNotNull(chapter.find(highlight = "the", before = "walked to ", after = " river"))
        assertEquals(chapter.text.indexOf("the river"), later.start)
    }

    // The KOReader plugin counts some characters the file's way (an entity as its letters): its place may be off a little.
    @Test
    fun `a place a little off finds its words nearest to it`() {
        val offPlace = checkNotNull(EpubCfi.parse("epubcfi(/6/8!/4/2[ch1]/6,/1:16,/1:25)")?.let(chapter::spanOf))
        assertTrue(!chapter.holds(offPlace, "river and"))

        val found = checkNotNull(chapter.find("river and", before = null, after = null, near = offPlace.start))

        assertEquals("river and", chapter.text.substring(found.start, found.end))
        val nearest = checkNotNull(chapter.find("the", before = null, after = null, near = chapter.text.indexOf("the river")))
        assertEquals(chapter.text.indexOf("the river"), nearest.start)
    }

    @Test
    fun `a quote not in the chapter isn't found`() {
        assertNull(chapter.find(highlight = "the burrow", before = null, after = null))
    }

    @Test
    fun `a CFI's words come with the text around them for Readium to find them`() {
        val span = checkNotNull(EpubCfi.parse("epubcfi(/6/8!/4/2[ch1]/4,/1:11,/1:23)")?.let(chapter::spanOf))
        val quote = chapter.quote(span)

        assertEquals("first summer", quote.highlight)
        assertTrue(quote.before.endsWith("It was the "))
        assertTrue(quote.after.startsWith(" of the hole."))
        assertTrue(quote.progression > 0.0 && quote.progression < 1.0)
    }

    @Test
    fun `the CFI spec's own example reads`() {
        val range = checkNotNull(EpubCfi.parse("epubcfi(/6/14[chap05ref]!/4[body01]/10[para05],/2/1:1,/3:4)"))

        assertEquals(6, range.spineIndex)
        assertEquals(listOf(CfiStep(4, "body01"), CfiStep(10, "para05"), CfiStep(2), CfiStep(1, offset = 1)), range.start)
        assertEquals(listOf(CfiStep(4, "body01"), CfiStep(10, "para05"), CfiStep(3, offset = 4)), range.end)
    }

    @Test
    fun `escaped ids and text assertions read, and ids are escaped when written`() {
        val range = checkNotNull(EpubCfi.parse("epubcfi(/6/4!/4[a^,b^]c]/2,/1:0[Once,upon],/1:4)"))

        assertEquals("a,b]c", range.start[0].id)
        assertEquals(CfiStep(1, offset = 0), range.start.last())
        assertEquals(
            "epubcfi(/6/4!/4[a^,b^]c],/1:0,/1:4)",
            EpubCfi.formatRange(listOf(CfiStep(6), CfiStep(4)), listOf(CfiStep(4, "a,b]c"), CfiStep(1, offset = 0)), listOf(CfiStep(4, "a,b]c"), CfiStep(1, offset = 4))),
        )
    }

    @Test
    fun `what isn't a range CFI in a chapter isn't read`() {
        assertNull(EpubCfi.parse("OEBPS/text/part03.xhtml"))
        assertNull(EpubCfi.parse("epubcfi(/6/8)"))
        assertNull(EpubCfi.parse("epubcfi(/6/8!/4,/1:0)"))
        assertNotNull(EpubCfi.parse("epubcfi(/6/8!/4/2/1:3)"))
    }

    @Test
    fun `the package's spine counts its non-linear items and names its items' ids`() {
        val container = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
        """.trimIndent().toByteArray()
        val opf = """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>The Hole</dc:title></metadata>
              <manifest>
                <item id="cover" href="Text/cover.xhtml" media-type="application/xhtml+xml"/>
                <item id="c1" href="Text/chapter%201.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="../Misc/notes.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine toc="ncx">
                <itemref idref="cover" linear="no"/>
                <itemref idref="c1" id="ref-c1"/>
                <itemref idref="c2"/>
              </spine>
            </package>
        """.trimIndent().toByteArray()

        val opfPath = checkNotNull(EpubPackageSpine.packagePath(container))
        val spine = checkNotNull(EpubPackageSpine.parse(opf, opfPath))

        assertEquals("OEBPS/content.opf", opfPath)
        assertEquals(listOf("OEBPS/Text/cover.xhtml", "OEBPS/Text/chapter 1.xhtml", "Misc/notes.xhtml"), spine.items.map { it.path })
        assertEquals(listOf(CfiStep(6), CfiStep(4, "ref-c1")), spine.items[1].packageSteps)
        assertEquals(1, spine.indexOf("OEBPS/Text/chapter 1.xhtml"))
    }
}
