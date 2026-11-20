package com.example.unifiedreader.feature.annotations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkStepsTest {
    private val hole = AnnotatedBook("grimmory:item:2694", "The Hole", "Hiroko Oyamada")

    private val early = highlight("h1", "the river", 0.10)
    private val bookmark = bookmark("b1", 0.30, title = null, chapter = "Chapter 3", page = 41)
    private val late = highlight("h2", "the  sumo\nmandarins", 0.70)
    private val fromServer = bookmark("b2", null, title = "unplaced", chapter = null, page = null).let { mark ->
        mark.copy(place = mark.place.copy(locatorJson = ""))
    }

    private val stops = markStops(listOf(late, early), listOf(fromServer, bookmark))

    @Test
    fun `highlights and bookmarks go together in reading order, ones with no place left out`() {
        assertEquals(listOf("h1", "b1", "h2"), stops.map(MarkStop::id))
        assertEquals("“the sumo mandarins”", stops.last().label)
        assertEquals("Chapter 3 · Page 41", stops[1].label)
    }

    @Test
    fun `stepping goes to the mark before or after, and stops at either end`() {
        val steps = MarkSteps()
        steps.update(stops)
        assertNull(steps.step(forward = true))

        steps.opened { it.locatorJson == bookmark.place.locatorJson }
        assertEquals("2 of 3", steps.position)
        assertEquals("h2", steps.step(forward = true)?.id)
        assertNull(steps.step(forward = true))
        assertEquals("b1", steps.step(forward = false)?.id)
        assertEquals("h1", steps.step(forward = false)?.id)
        assertNull(steps.step(forward = false))
        assertEquals("1 of 3", steps.position)
    }

    @Test
    fun `a mark opened before the marks load is found once they have, and a word hides the strip`() {
        val steps = MarkSteps()
        steps.opened { it.id == "h2" }
        assertNull(steps.current)

        steps.update(stops)
        assertEquals("h2", steps.current?.id)

        steps.opened { it.id == "a saved word" }
        assertNull(steps.current)
        assertNull(steps.position)
    }

    @Test
    fun `a mark removed after it was opened still steps on from its place`() {
        val steps = MarkSteps()
        steps.update(stops)
        steps.opened { it.id == "b1" }

        steps.update(stops.filterNot { it.id == "b1" })
        assertEquals("2 marks", steps.position)
        assertEquals("h2", steps.step(forward = true)?.id)

        steps.close()
        assertNull(steps.shown)
    }

    private fun highlight(id: String, text: String, progression: Double) = ReaderHighlight(
        id = id,
        book = hole,
        place = AnnotationPlace("c.xhtml", """{"href":"c.xhtml","p":$progression}""", "Chapter", progression),
        text = text,
        style = HighlightStyle.Highlight,
        color = HighlightColor.Yellow,
        note = null,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
    )

    private fun bookmark(
        id: String,
        progression: Double?,
        title: String?,
        chapter: String?,
        page: Int?,
    ) = ReaderBookmark(
        id = id,
        book = hole,
        place = AnnotationPlace("c.xhtml", """{"href":"c.xhtml","p":$progression}""", chapter, progression),
        page = page,
        pageCount = 200,
        text = null,
        createdAtEpochMillis = 1,
        title = title,
    )
}
