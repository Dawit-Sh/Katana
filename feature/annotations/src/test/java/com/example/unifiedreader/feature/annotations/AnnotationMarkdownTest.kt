package com.example.unifiedreader.feature.annotations

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class AnnotationMarkdownTest {
    private val hole = AnnotatedBook("grimmory:item:2694", "The Hole", "Hiroko Oyamada")
    private val day = LocalDate.of(2026, 11, 18)

    @Test
    fun `a book exports its quotes in reading order under their chapters, notes under quotes, then its words`() {
        val markdown = AnnotationMarkdown.book(
            book = hole,
            highlights = listOf(
                highlight("b", "The rent was a lot less\nthan our tiny apartment.", chapter = "Chapter 1", at = 0.3),
                highlight("a", "a box of the most beautiful sumo mandarins", chapter = "Chapter 1", at = 0.1, note = "The Katos."),
                highlight("c", "The black animal.", chapter = "Chapter 2", at = 0.6),
            ),
            words = listOf(word("liminal", "A liminal  field.", chapter = "Chapter 2", at = 0.5)),
            exportedOn = day,
        )

        assertEquals(
            """
            # The Hole
            *Hiroko Oyamada*

            Exported from Katana on 18 November 2026: 3 highlights, 1 saved word.

            ## Highlights

            ### Chapter 1

            > a box of the most beautiful sumo mandarins

            Note: The Katos.

            > The rent was a lot less
            > than our tiny apartment.

            ### Chapter 2

            > The black animal.

            ## Words

            - **liminal**: "A liminal field." (Chapter 2)

            """.trimIndent(),
            markdown,
        )
    }

    @Test
    fun `the notebook has every book in title order with its own sections`() {
        val factory = AnnotatedBook("grimmory:item:7", "the Factory", "Hiroko Oyamada")
        val markdown = AnnotationMarkdown.library(
            highlights = listOf(highlight("a", "Quote one.", chapter = null, at = 0.2)),
            words = listOf(word("sere", null, chapter = null, at = null, book = factory)),
            exportedOn = day,
        )

        assertEquals(
            """
            # Katana notebook

            Exported from Katana on 18 November 2026: 1 highlight, 1 saved word.

            ## the Factory
            *Hiroko Oyamada*

            ### Words

            - **sere**

            ## The Hole
            *Hiroko Oyamada*

            ### Highlights

            > Quote one.

            """.trimIndent(),
            markdown,
        )
    }

    @Test
    fun `file names keep the title readable and drop characters a file system refuses`() {
        assertEquals("The Hole 2026-11-18.md", AnnotationMarkdown.fileName(hole, day))
        assertEquals(
            "Who Am I Really 2026-11-18.md",
            AnnotationMarkdown.fileName(hole.copy(title = "Who Am I: Really?"), day),
        )
        assertEquals("Katana notebook 2026-11-18.md", AnnotationMarkdown.fileName(null, day))
    }

    private fun highlight(id: String, text: String, chapter: String?, at: Double?, note: String? = null) = ReaderHighlight(
        id = id,
        book = hole,
        place = AnnotationPlace(href = "c.xhtml", locatorJson = "{}", chapterTitle = chapter, progression = at),
        text = text,
        style = HighlightStyle.Highlight,
        color = HighlightColor.Yellow,
        note = note,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
    )

    private fun word(word: String, sentence: String?, chapter: String?, at: Double?, book: AnnotatedBook = hole) = SavedWord(
        id = word,
        book = book,
        word = word,
        sentence = sentence,
        place = AnnotationPlace(href = "", locatorJson = "{}", chapterTitle = chapter, progression = at),
        note = null,
        createdAtEpochMillis = 1,
    )
}
