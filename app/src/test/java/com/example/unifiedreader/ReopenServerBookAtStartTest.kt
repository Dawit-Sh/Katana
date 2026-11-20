package com.example.unifiedreader

import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ReopenServerBookAtStartTest {
    private val serverBook = ReadableItem(
        id = "grimmory:2694",
        sourceType = SourceType.GRIMMORY,
        libraryId = "grimmory:1",
        title = "The Hole",
        format = FormatType.EPUB,
    )
    private val localBook = ReadableItem(
        id = "local:alice",
        sourceType = SourceType.LOCAL,
        libraryId = "local",
        title = "Alice's Adventures in Wonderland",
        format = FormatType.EPUB,
    )
    private val asksServer: (ReadableItem) -> Boolean = { it.sourceType != SourceType.LOCAL }

    private fun startingInside(book: ReadableItem) = AppUiState(
        items = listOf(serverBook, localBook),
        selectedSource = book.sourceType,
        selectedItemId = book.id,
        activeReader = book.format,
    )

    // His Pixel and Fire, 2026-11-20: Katana reopened The Hole at this device's page, not the newer one on Grimmory.
    @Test
    fun `a server's book open at start opens again the library's way, asking the server first`() {
        val (start, toOpen) = startingInside(serverBook).reopeningServerBookThroughOpen(asksServer)

        assertEquals(serverBook, toOpen)
        assertNull(start.activeReader)
        assertNull(start.selectedItemId)
        assertNull(start.selectedReaderItem)
        assertEquals(SourceType.GRIMMORY, start.selectedSource)
    }

    @Test
    fun `a book on this device reopens straight away, as before`() {
        val state = startingInside(localBook)

        val (start, toOpen) = state.reopeningServerBookThroughOpen(asksServer)

        assertNull(toOpen)
        assertSame(state, start)
    }

    @Test
    fun `no book open at start, nothing to open`() {
        val state = AppUiState(items = listOf(serverBook, localBook))

        val (start, toOpen) = state.reopeningServerBookThroughOpen(asksServer)

        assertNull(toOpen)
        assertSame(state, start)
    }

    // A book the catalog hasn't listed yet (a cold start loads it later) is found through the kept reader item.
    @Test
    fun `a server's book not yet in the catalog is still opened the library's way`() {
        val state = AppUiState(
            items = emptyList(),
            selectedSource = SourceType.GRIMMORY,
            selectedItemId = serverBook.id,
            activeReader = serverBook.format,
            selectedReaderItem = serverBook,
        )

        val (start, toOpen) = state.reopeningServerBookThroughOpen(asksServer)

        assertEquals(serverBook, toOpen)
        assertNull(start.activeReader)
        assertNull(start.selectedReaderItem)
    }
}
