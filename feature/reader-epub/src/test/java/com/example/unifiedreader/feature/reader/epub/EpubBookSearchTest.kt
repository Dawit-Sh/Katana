package com.example.unifiedreader.feature.reader.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubBookSearchTest {
    @Test
    fun `a snippet keeps short context whole`() {
        val snippet = epubSearchSnippet(before = "to the ", match = "bento", after = " shop.")

        assertEquals(EpubSearchSnippet(before = "to the ", match = "bento", after = " shop."), snippet)
    }

    @Test
    fun `a snippet cuts long context at whole words and marks the cuts`() {
        val snippet = epubSearchSnippet(
            before = "Every morning at the same hour he walked along the river and crossed the bridge to the ",
            match = "bento",
            after = " shop. He always ordered the same thing and never looked up for long, not even once.",
            maxBefore = 30,
            maxAfter = 30,
        )

        assertEquals("…crossed the bridge to the ", snippet.before)
        assertEquals("bento", snippet.match)
        assertEquals(" shop. He always ordered the…", snippet.after)
    }

    @Test
    fun `a snippet folds line breaks and runs of spaces into one space`() {
        val snippet = epubSearchSnippet(before = "\n  the\n\n", match = "old\nman", after = "  went\thome\n")

        assertEquals("the ", snippet.before)
        assertEquals("old man", snippet.match)
        assertEquals(" went home", snippet.after)
    }

    @Test
    fun `two letters start a search, or one character of a script without spaces`() {
        assertFalse(isEpubSearchQueryLongEnough(""))
        assertFalse(isEpubSearchQueryLongEnough(" a "))
        assertTrue(isEpubSearchQueryLongEnough("ab"))
        assertTrue(isEpubSearchQueryLongEnough("刀"))
    }

    @Test
    fun `the summary says how many results in how many chapters`() {
        assertNull(epubSearchSummary(EpubSearchStatus.Idle, 0, 0))
        assertEquals("Searching…", epubSearchSummary(EpubSearchStatus.Searching, 0, 0))
        assertEquals("4 results so far", epubSearchSummary(EpubSearchStatus.Searching, 4, 1))
        assertEquals("12 results in 3 chapters", epubSearchSummary(EpubSearchStatus.Done, 12, 3))
        assertEquals("1 result in 1 chapter", epubSearchSummary(EpubSearchStatus.Done, 1, 1))
        assertEquals("No results", epubSearchSummary(EpubSearchStatus.Done, 0, 0))
        assertEquals("The first 500 results, in 40 chapters", epubSearchSummary(EpubSearchStatus.Capped, 500, 40))
        assertEquals("This book can't be searched", epubSearchSummary(EpubSearchStatus.Failed, 0, 0))
    }

    @Test
    fun `the position counts from one`() {
        assertEquals("3 of 12", epubSearchPosition(currentIndex = 2, resultCount = 12))
        assertEquals("12 results", epubSearchPosition(currentIndex = null, resultCount = 12))
        assertEquals("1 result", epubSearchPosition(currentIndex = null, resultCount = 1))
    }

    @Test
    fun `stepping goes round past the ends`() {
        val progressions = listOf(0.1, 0.4, 0.8)

        assertEquals(1, nextEpubSearchIndex(progressions, currentIndex = 0, forward = true, fromProgress = 0.0))
        assertEquals(0, nextEpubSearchIndex(progressions, currentIndex = 2, forward = true, fromProgress = 0.0))
        assertEquals(2, nextEpubSearchIndex(progressions, currentIndex = 0, forward = false, fromProgress = 0.0))
    }

    @Test
    fun `before a match is chosen, stepping starts from the reader's place`() {
        val progressions = listOf(0.1, 0.4, 0.8)

        assertEquals(1, nextEpubSearchIndex(progressions, currentIndex = null, forward = true, fromProgress = 0.3))
        assertEquals(0, nextEpubSearchIndex(progressions, currentIndex = null, forward = false, fromProgress = 0.3))
        // Past the last match, forward goes round to the first; before the first, back goes to the last.
        assertEquals(0, nextEpubSearchIndex(progressions, currentIndex = null, forward = true, fromProgress = 0.9))
        assertEquals(2, nextEpubSearchIndex(progressions, currentIndex = null, forward = false, fromProgress = 0.05))
    }

    @Test
    fun `no results, no step`() {
        assertNull(nextEpubSearchIndex(emptyList(), currentIndex = null, forward = true, fromProgress = 0.5))
    }
}
