package com.example.unifiedreader.feature.reader.epub

import org.junit.Assert.assertEquals
import org.junit.Test

class EpubChapterPullTest {
    private val threshold = 100f

    @Test
    fun `a drag up that began at the chapter's end goes on into the next chapter`() {
        assertEquals(EpubChapterPullTurn.Next, epubChapterPullTurn(EpubChapterEdge.End, dragX = 10f, dragY = -300f, thresholdPx = threshold))
        assertEquals(EpubChapterPullTurn.Next, epubChapterPullTurn(EpubChapterEdge.Both, dragX = 0f, dragY = -300f, thresholdPx = threshold))
    }

    @Test
    fun `a drag down that began at the chapter's top goes back to the one before`() {
        assertEquals(EpubChapterPullTurn.Previous, epubChapterPullTurn(EpubChapterEdge.Top, dragX = 0f, dragY = 300f, thresholdPx = threshold))
        assertEquals(EpubChapterPullTurn.Previous, epubChapterPullTurn(EpubChapterEdge.Both, dragX = 0f, dragY = 300f, thresholdPx = threshold))
    }

    @Test
    fun `scrolling inside a chapter, short drags and sideways swipes turn nothing`() {
        // Reaching the end on the way is a scroll: the edge counts where the finger came down.
        assertEquals(EpubChapterPullTurn.None, epubChapterPullTurn(EpubChapterEdge.Inside, dragX = 0f, dragY = -900f, thresholdPx = threshold))
        assertEquals(EpubChapterPullTurn.None, epubChapterPullTurn(EpubChapterEdge.End, dragX = 0f, dragY = -40f, thresholdPx = threshold))
        assertEquals(EpubChapterPullTurn.None, epubChapterPullTurn(EpubChapterEdge.End, dragX = -400f, dragY = -150f, thresholdPx = threshold))
        // The wrong way at an edge.
        assertEquals(EpubChapterPullTurn.None, epubChapterPullTurn(EpubChapterEdge.End, dragX = 0f, dragY = 300f, thresholdPx = threshold))
        assertEquals(EpubChapterPullTurn.None, epubChapterPullTurn(EpubChapterEdge.Top, dragX = 0f, dragY = -300f, thresholdPx = threshold))
    }
}
