package com.example.unifiedreader.feature.reader.epub

import org.junit.Assert.assertEquals
import org.junit.Test

class EpubChapterPullTest {
    private fun turn(edge: EpubChapterEdge, dragX: Float, dragY: Float) =
        epubChapterPullTurn(edge, dragX = dragX, dragY = dragY, thresholdPx = 100f)

    @Test
    fun `a drag up that began at the chapter's end goes on into the next chapter`() {
        assertEquals(EpubChapterPullTurn.Next, turn(EpubChapterEdge.End, 10f, -300f))
        assertEquals(EpubChapterPullTurn.Next, turn(EpubChapterEdge.Both, 0f, -300f))
    }

    @Test
    fun `a drag down that began at the chapter's top goes back to the one before`() {
        assertEquals(EpubChapterPullTurn.Previous, turn(EpubChapterEdge.Top, 0f, 300f))
        assertEquals(EpubChapterPullTurn.Previous, turn(EpubChapterEdge.Both, 0f, 300f))
    }

    @Test
    fun `scrolling inside a chapter, short drags and sideways swipes turn nothing`() {
        // Reaching the end on the way is a scroll: the edge counts where the finger came down.
        assertEquals(EpubChapterPullTurn.None, turn(EpubChapterEdge.Inside, 0f, -900f))
        assertEquals(EpubChapterPullTurn.None, turn(EpubChapterEdge.End, 0f, -40f))
        assertEquals(EpubChapterPullTurn.None, turn(EpubChapterEdge.End, -400f, -150f))
        // The wrong way at an edge.
        assertEquals(EpubChapterPullTurn.None, turn(EpubChapterEdge.End, 0f, 300f))
        assertEquals(EpubChapterPullTurn.None, turn(EpubChapterEdge.Top, 0f, -300f))
    }
}
