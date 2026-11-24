package com.example.unifiedreader

import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.core.model.LocatorType
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.feature.reader.core.ReaderChapterPosition
import com.example.unifiedreader.feature.reader.core.ReaderProgressSnapshot
import com.example.unifiedreader.feature.reader.core.ReaderSessionSnapshot
import com.example.unifiedreader.feature.reader.core.readerSessionChapterPreferences
import com.example.unifiedreader.feature.reader.core.readerSessionShownPagePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppProgressSummariesTest {
    @Test
    fun `a place that came from another device names the chapter it is in`() {
        // twelve chapters, the reader last saved chapter 8 (it starts at 7/12)
        val starts = (0 until 12).map { it / 12f }
        val saved = ReaderChapterPosition(index = 8, count = 12, title = "Chapter 8", starts = starts)
        // the desk's place came down at 83%: chapter 10
        assertEquals(10, chapterOfShare(0.8333f, saved))
        // in the saved chapter, or a hair past its edges (rounded shares): the saved one stands
        assertNull(chapterOfShare(0.62f, saved))
        assertNull(chapterOfShare(7 / 12f - 0.001f, saved))
        assertNull(chapterOfShare(8 / 12f + 0.001f, saved))
        // an earlier chapter, and no starts known
        assertEquals(1, chapterOfShare(0.02f, saved))
        assertNull(chapterOfShare(0.9f, saved.copy(starts = emptyList())))
    }

    private val book = ReadableItem(
        id = "local:book",
        sourceType = SourceType.LOCAL,
        libraryId = "local",
        title = "The Salt Orchard",
        format = FormatType.EPUB,
    )

    @Test
    fun `an EPUB summary carries the chapter its reader saved`() {
        val summary = summaries(isFinished = false).getValue(book.id)

        assertEquals(3, summary.chapter)
        assertEquals(6, summary.chapterCount)
        assertEquals("Low Tide", summary.chapterTitle)
        assertEquals(chapterStarts, summary.chapterStarts)
    }

    @Test
    fun `a finished book drops the chapter but keeps how many it has and where they start`() {
        val summary = summaries(isFinished = true).getValue(book.id)

        assertNull(summary.chapter)
        assertNull(summary.chapterTitle)
        assertEquals(6, summary.chapterCount)
        assertEquals(chapterStarts, summary.chapterStarts)
    }

    @Test
    fun `a place from Grimmory shows the page the reader showed there`() {
        // The Hole on his Pixel: Grimmory keeps 6.4%, the reader opened on page 6 of 78, the card said page 4.
        val fromServer = ReaderProgressSnapshot(itemId = book.id, locatorType = LocatorType.CFI, percent = 0.064)
        fun card(shown: Map<String, String>) = buildProgressSummaries(
            items = listOf(book),
            progressByItemId = mapOf(book.id to fromServer),
            sessionByItemId = mapOf(book.id to ReaderSessionSnapshot(book.id, pageCount = 78, preferences = shown)),
            localPageCountProvider = { 0 },
        ).getValue(book.id).shownPage

        assertEquals(6, card(readerSessionShownPagePreferences(page = 6, savedPercent = 0.064)))
        // Another device moved the place on since: the page shown at the old place no longer counts.
        assertEquals(5, card(readerSessionShownPagePreferences(page = 6, savedPercent = 0.05)))
        assertEquals(5, card(emptyMap()))
    }

    private val chapterStarts = listOf(0f, 0.1f, 0.24f, 0.38f, 0.61f, 0.8f)

    private fun summaries(isFinished: Boolean) = buildProgressSummaries(
        items = listOf(book),
        progressByItemId = mapOf(
            book.id to ReaderProgressSnapshot(
                itemId = book.id,
                locatorType = LocatorType.CFI,
                page = 22,
                // inside chapter 3 (it runs from 24% to 38%), where the reader saved it
                percent = 0.30,
                isFinished = isFinished,
            ),
        ),
        sessionByItemId = mapOf(
            book.id to ReaderSessionSnapshot(
                itemId = book.id,
                pageCount = 73,
                preferences = readerSessionChapterPreferences(
                    ReaderChapterPosition(index = 3, count = 6, title = "Low Tide", starts = chapterStarts),
                ),
            ),
        ),
        localPageCountProvider = { 0 },
    )
}
