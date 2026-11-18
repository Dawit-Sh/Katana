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
        assertTrue(currentElement, currentElement.startsWith("<div style=\"background-color: rgba(232, 163, 61, 0.55);"))
        assertTrue(currentElement, "box-shadow" in currentElement)
        assertTrue(otherElement, otherElement.startsWith("<div style=\"background-color: rgba(232, 163, 61, 0.22);"))
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

    private fun match(href: String) = EpubSearchResult(
        locator = Locator(
            href = checkNotNull(Url(href)),
            mediaType = MediaType.XHTML,
            text = Locator.Text(before = "the ", highlight = "bento", after = " shop"),
        ),
        snippet = EpubSearchSnippet(before = "the ", match = "bento", after = " shop"),
    )
}
