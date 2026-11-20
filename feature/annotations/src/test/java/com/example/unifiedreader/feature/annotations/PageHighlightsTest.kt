package com.example.unifiedreader.feature.annotations

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// org.json is Android's, so these run on Robolectric.
@RunWith(RobolectricTestRunner::class)
class PageHighlightsTest {
    private val spot = PageTextSpot(
        pageIndex = 11,
        start = 120,
        end = 164,
        rects = listOf(
            PageTextRect(0.123456f, 0.2f, 0.876543f, 0.225f),
            PageTextRect(0.1f, 0.23f, 0.41f, 0.255f),
        ),
    )

    @Test
    fun `a highlight on turned words keeps which way they run, and an upright one stays as it was`() {
        val turned = pageTextSpotOf(pageHighlightPlace(spot.copy(turn = 1), pageCount = 300, pageCharCount = 2400).locatorJson)!!
        val upright = pageHighlightPlace(spot, pageCount = 300, pageCharCount = 2400).locatorJson

        assertEquals(1, turned.turn)
        assertEquals(0, pageTextSpotOf(upright)!!.turn)
        assertTrue("an upright highlight's place has no turn", !upright.contains("turn"))
    }

    @Test
    fun `a page highlight's place keeps its page, characters and lines`() {
        val place = pageHighlightPlace(spot, pageCount = 300, pageCharCount = 2400)

        val read = pageTextSpotOf(place.locatorJson)!!
        assertEquals(11, read.pageIndex)
        assertEquals(120, read.start)
        assertEquals(164, read.end)
        assertEquals(2, read.rects.size)
        spot.rects.zip(read.rects).forEach { (made, kept) ->
            assertEquals(made.left, kept.left, Precision)
            assertEquals(made.top, kept.top, Precision)
            assertEquals(made.right, kept.right, Precision)
            assertEquals(made.bottom, kept.bottom, Precision)
        }
        assertEquals("page:11", place.href)
        assertEquals("Page 12", place.chapterTitle)
    }

    @Test
    fun `the Bookmarks page opens a page highlight at its page, as a page bookmark`() {
        val place = pageHighlightPlace(spot, pageCount = 300, pageCharCount = 2400)

        assertEquals(11, pageIndexOfBookmarkLocator(place.locatorJson))
    }

    @Test
    fun `highlights order by page, then by where on the page they start`() {
        fun progression(page: Int, start: Int) =
            pageHighlightPlace(spot.copy(pageIndex = page, start = start), pageCount = 10, pageCharCount = 1000).progression!!

        assertTrue(progression(2, 900) < progression(3, 0))
        assertTrue(progression(3, 10) < progression(3, 500))
        assertTrue(progression(9, 999) <= 1.0)
        assertEquals(0.0, progression(0, 0), 0.0)
    }

    @Test
    fun `other places are not page highlights`() {
        assertNull(pageTextSpotOf("{\"href\":\"c1.xhtml\",\"locations\":{\"progression\":0.2}}"))
        assertNull(pageTextSpotOf(pageBookmarkPlace(3, 10).locatorJson))
        assertNull(pageTextSpotOf("not json"))
        assertNull(pageTextSpotOf("{\"page\":2,\"rects\":[]}"))
    }

    @Test
    fun `a PDF's highlights export under their pages, next to an EPUB's under chapters`() {
        val pdf = AnnotatedBook("pdf-1", "Field Notes", null)
        val epub = AnnotatedBook("epub-1", "The Hole", "Hiroko Oyamada")
        fun highlight(id: String, book: AnnotatedBook, place: AnnotationPlace, text: String) = ReaderHighlight(
            id = id,
            book = book,
            place = place,
            text = text,
            style = HighlightStyle.Highlight,
            color = HighlightColor.Yellow,
            note = null,
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )
        val highlights = listOf(
            highlight("b", pdf, pageHighlightPlace(spot.copy(pageIndex = 4), 10, 100), "later words"),
            highlight("a", pdf, pageHighlightPlace(spot.copy(pageIndex = 1), 10, 100), "early words"),
            highlight("c", epub, AnnotationPlace("c1.xhtml", "{}", "Chapter 1", 0.1), "sumo mandarins"),
        )

        val markdown = AnnotationMarkdown.library(highlights, emptyList(), LocalDate.of(2026, 11, 20))

        val pdfPart = markdown.substringAfter("## Field Notes").substringBefore("## The Hole")
        assertTrue(pdfPart, pdfPart.indexOf("#### Page 2") < pdfPart.indexOf("> early words"))
        assertTrue(pdfPart, pdfPart.indexOf("> early words") < pdfPart.indexOf("#### Page 5"))
        assertTrue(pdfPart, pdfPart.indexOf("#### Page 5") < pdfPart.indexOf("> later words"))
        assertTrue(markdown, markdown.substringAfter("## The Hole").contains("#### Chapter 1"))
    }

    private companion object {
        const val Precision = 0.00001f
    }
}
