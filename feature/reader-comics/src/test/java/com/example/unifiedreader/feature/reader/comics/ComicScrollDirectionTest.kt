package com.example.unifiedreader.feature.reader.comics

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.feature.reader.core.ReaderSessionSnapshot
import com.example.unifiedreader.feature.reader.preferences.ComicReaderMode
import com.example.unifiedreader.feature.reader.preferences.ComicReadingDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicScrollDirectionTest {
    @Test
    fun `turning pages offers left and right, scrolling also offers top to bottom`() {
        assertEquals(
            listOf(ComicDirectionChoice.LeftToRight, ComicDirectionChoice.RightToLeft),
            comicDirectionChoices(ComicReaderMode.Paged),
        )
        assertEquals(
            listOf(ComicDirectionChoice.TopToBottom, ComicDirectionChoice.LeftToRight, ComicDirectionChoice.RightToLeft),
            comicDirectionChoices(ComicReaderMode.VerticalScroll),
        )
        assertEquals(listOf("Top to bottom", "Left to right", "Right to left"), ComicDirectionChoice.entries.map { it.label })
    }

    @Test
    fun `the picked direction follows the mode, the reading direction and the axis`() {
        val scroll = ComicReaderMode.VerticalScroll
        val turn = ComicReaderMode.Paged
        assertEquals(ComicDirectionChoice.TopToBottom, comicDirectionChoice(scroll, ComicReadingDirection.RTL, ComicScrollAxis.Vertical))
        assertEquals(ComicDirectionChoice.RightToLeft, comicDirectionChoice(scroll, ComicReadingDirection.RTL, ComicScrollAxis.Horizontal))
        assertEquals(ComicDirectionChoice.LeftToRight, comicDirectionChoice(scroll, ComicReadingDirection.LTR, ComicScrollAxis.Horizontal))
        // Turning pages, the axis doesn't show: a manga stays right to left.
        assertEquals(ComicDirectionChoice.RightToLeft, comicDirectionChoice(turn, ComicReadingDirection.RTL, ComicScrollAxis.Vertical))
    }

    @Test
    fun `top to bottom keeps the reading direction for when the comic turns pages again`() {
        val pick = comicDirectionPick(ComicDirectionChoice.TopToBottom, ComicReaderMode.VerticalScroll)
        assertEquals(ComicScrollAxis.Vertical, pick.axis)
        assertEquals(null, pick.direction)
    }

    @Test
    fun `left or right turns a scrolling strip sideways, and only sets the direction when turning pages`() {
        val scrolling = comicDirectionPick(ComicDirectionChoice.RightToLeft, ComicReaderMode.VerticalScroll)
        assertEquals(ComicScrollAxis.Horizontal, scrolling.axis)
        assertEquals(ComicReadingDirection.RTL, scrolling.direction)

        val turning = comicDirectionPick(ComicDirectionChoice.LeftToRight, ComicReaderMode.Paged)
        assertEquals(null, turning.axis)
        assertEquals(ComicReadingDirection.LTR, turning.direction)
    }

    @Test
    fun `the slider runs the way the pages go`() {
        val scroll = ComicReaderMode.VerticalScroll
        assertEquals(ComicReadingDirection.LTR, comicProgressBarDirection(scroll, ComicReadingDirection.RTL, ComicScrollAxis.Vertical))
        assertEquals(ComicReadingDirection.RTL, comicProgressBarDirection(scroll, ComicReadingDirection.RTL, ComicScrollAxis.Horizontal))
        assertEquals(ComicReadingDirection.RTL, comicProgressBarDirection(ComicReaderMode.Paged, ComicReadingDirection.RTL))
    }

    @Test
    fun `a pull past the end of a sideways strip is toward its end`() {
        assertEquals(-50f, continuousForwardPull(Offset(10f, -50f), ComicScrollAxis.Vertical, rightToLeft = false))
        assertEquals(-50f, continuousForwardPull(Offset(-50f, 10f), ComicScrollAxis.Horizontal, rightToLeft = false))
        // Right to left, the strip's end is on the left: the finger moves right.
        assertEquals(-50f, continuousForwardPull(Offset(50f, 10f), ComicScrollAxis.Horizontal, rightToLeft = true))
    }

    @Test
    fun `a sideways page fits whole in the view`() {
        val phone = DpSize(width = 400.dp, height = 860.dp)
        val portraitPage = sidewaysComicPageSize(phone, aspect = 2f / 3f)
        assertEquals(400f, portraitPage.width.value, 0.01f)
        assertEquals(600f, portraitPage.height.value, 0.01f)

        val landscapeScreen = DpSize(width = 1200.dp, height = 700.dp)
        val tallOnWide = sidewaysComicPageSize(landscapeScreen, aspect = 2f / 3f)
        assertEquals(700f, tallOnWide.height.value, 0.01f)
        assertTrue(tallOnWide.width < landscapeScreen.width)
    }

    @Test
    fun `the scroll axis is kept with the book and defaults to top to bottom`() {
        assertEquals(ComicScrollAxis.Vertical, restoreComicScrollAxis(emptyMap()))
        assertEquals(ComicScrollAxis.Horizontal, restoreComicScrollAxis(mapOf(ComicsScrollAxisKey to "Horizontal")))
        assertEquals(ComicScrollAxis.Vertical, restoreComicScrollAxis(mapOf(ComicsScrollAxisKey to "Diagonal")))

        val saved = restoreComicReaderPreferences(
            ReaderSessionSnapshot(itemId = "issue", preferences = mapOf(ComicsScrollAxisKey to "Horizontal")),
        )
        assertEquals(ComicScrollAxis.Horizontal, saved?.scrollAxis)
        val roundTrip = restoreComicReaderPreferences(buildComicReaderPreferencesMap(requireNotNull(saved)))
        assertEquals(ComicScrollAxis.Horizontal, roundTrip?.scrollAxis)
    }

    @Test
    fun `turning the strip keeps the page and starts at its top`() {
        val state = ComicsReaderState(
            initialPage = 12,
            pageCount = 40,
            initialReaderMode = ComicReaderMode.VerticalScroll,
            initialReadingDirection = ComicReadingDirection.RTL,
            initialPageFitMode = ComicPageFitMode.FitPage,
            initialSpreadMode = ComicSpreadMode.Auto,
            initialSplitWidePages = false,
            initialResumeAnchorPage = 12,
            initialResumeSegment = null,
            initialResumeScrollOffsetRatio = 0.4f,
        )
        state.onScrollAxisChanged(ComicScrollAxis.Horizontal)

        assertEquals(ComicScrollAxis.Horizontal, state.scrollAxis)
        assertEquals(12, state.currentPage)
        assertEquals(12, state.resumeAnchorPage)
        assertEquals(null, state.resumeScrollOffsetRatio)
        // A saved axis arriving later doesn't undo the reader's pick.
        assertFalse(state.restoreScrollAxisIfUnchanged(ComicScrollAxis.Vertical))
    }
}
