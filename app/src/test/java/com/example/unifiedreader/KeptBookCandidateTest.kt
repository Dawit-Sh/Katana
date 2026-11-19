package com.example.unifiedreader

import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.core.model.LocatorType
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.feature.reader.core.ReaderProgressSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class KeptBookCandidateTest {
    @Test
    fun `a book opened but never read counts from when its copy was kept`() {
        val candidate = keptBookCandidate(
            item = book(),
            progress = null,
            isDownload = false,
            keptSinceEpochMillis = KEPT_AT,
        )

        assertEquals(KEPT_AT, candidate.openedAtEpochMillis)
        assertNull(candidate.readPercent)
        assertFalse(candidate.isFinished)
    }

    @Test
    fun `a book with a place keeps the time of its last read`() {
        val candidate = keptBookCandidate(
            item = book(),
            progress = ReaderProgressSnapshot(
                itemId = BOOK_ID,
                locatorType = LocatorType.PAGE,
                page = 3,
                percent = 0.25,
                updatedAtEpochMillis = READ_AT,
            ),
            isDownload = false,
            keptSinceEpochMillis = KEPT_AT,
        )

        assertEquals(READ_AT, candidate.openedAtEpochMillis)
        assertEquals(25, candidate.readPercent)
    }

    @Test
    fun `a download never opened still says so`() {
        val candidate = keptBookCandidate(
            item = book(),
            progress = null,
            isDownload = true,
            keptSinceEpochMillis = KEPT_AT,
        )

        assertNull(candidate.openedAtEpochMillis)
    }

    private fun book(): ReadableItem = ReadableItem(
        id = BOOK_ID,
        sourceType = SourceType.GRIMMORY,
        libraryId = "grimmory:library:1",
        remoteId = "290",
        remoteFileType = "PDF",
        title = "Earth: Portrait of a Planet",
        format = FormatType.PDF,
        pageCount = 1025,
    )

    private companion object {
        const val BOOK_ID = "grimmory:item:290"
        const val KEPT_AT = 1_763_540_000_000L
        const val READ_AT = 1_763_550_000_000L
    }
}
