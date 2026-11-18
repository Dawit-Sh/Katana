package com.example.unifiedreader.feature.reader.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EpubSearchDecorationsTest {
    @Test
    fun `every match is tinted, the current one marked`() {
        val decorations = epubSearchDecorations(listOf(match("a.xhtml"), match("a.xhtml"), match("b.xhtml")), currentIndex = 1)

        assertEquals(listOf("search-0", "search-1", "search-2"), decorations.map { it.id })
        assertEquals(listOf(false, true, false), decorations.map { (it.style as EpubSearchMatchStyle).current })
    }

    @Test
    fun `the current match is stronger and underlined, drawn on Readium's own box`() {
        val current = epubSearchDecorations(listOf(match("a.xhtml")), currentIndex = 0).single()
        val other = epubSearchDecorations(listOf(match("a.xhtml")), currentIndex = null).single()

        val currentElement = searchMatchElement(current)
        val otherElement = searchMatchElement(other)
        val fill = "<div style=\"background-color: rgba(232, 163, 61,"
        assertTrue(currentElement, currentElement.startsWith("$fill 0.55) !important;"))
        assertTrue(currentElement, "box-shadow" in currentElement)
        assertTrue(otherElement, otherElement.startsWith("$fill 0.22) !important;"))
        assertFalse(otherElement, "box-shadow" in otherElement)
        // One element, no child: a child inside Readium's box didn't show on the Fire's WebView 98.
        assertFalse(currentElement.substringAfter('>').contains('<' + "div"))
    }

    @Test
    fun `matches in a row in one chapter share a heading`() {
        val results = listOf(match("a.xhtml"), match("a.xhtml"), match("b.xhtml"), match("a.xhtml"))

        val groups = epubSearchGroups(results) { result -> result.locator.href.toString() }

        assertEquals(
            listOf(
                EpubSearchGroup("a.xhtml", listOf(0, 1)),
                EpubSearchGroup("b.xhtml", listOf(2)),
                EpubSearchGroup("a.xhtml", listOf(3)),
            ),
            groups,
        )
    }

    @Test
    fun `a match is on the last page of its file that starts at or before it`() {
        val pages = listOf(position(3, 0.0), position(4, 0.25), position(5, 0.5), position(6, 0.75))

        assertEquals(3, epubSearchPage(pages, progression = 0.1))
        assertEquals(5, epubSearchPage(pages, progression = 0.5))
        assertEquals(6, epubSearchPage(pages, progression = 0.99))
        assertEquals(3, epubSearchPage(pages, progression = null))
        assertEquals(null, epubSearchPage(emptyList(), progression = 0.4))
    }

    private fun position(page: Int, progression: Double) = Locator(
        href = checkNotNull(Url("a.xhtml")),
        mediaType = MediaType.XHTML,
        locations = Locator.Locations(progression = progression, position = page),
    )

    private fun match(href: String) = EpubSearchResult(
        locator = Locator(
            href = checkNotNull(Url(href)),
            mediaType = MediaType.XHTML,
            text = Locator.Text(before = "the ", highlight = "bento", after = " shop"),
        ),
        snippet = EpubSearchSnippet(before = "the ", match = "bento", after = " shop"),
    )
}
