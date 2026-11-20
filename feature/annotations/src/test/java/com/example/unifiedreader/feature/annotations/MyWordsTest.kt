package com.example.unifiedreader.feature.annotations

import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MyWordsTest {
    private val carol = AnnotatedBook("grimmory:item:1", "A Christmas Carol", "Charles Dickens")
    private val hole = AnnotatedBook("grimmory:item:2", "The Hole", "Hiroko Oyamada")
    private val now = 10L * DAY

    private val ironmongery = word("w1", carol, "ironmongery", "the deadliest piece of ironmongery in the trade", at = 5 * DAY)
    private val rime = word("w2", carol, "rime", "A frosty rime was on his head", at = 7 * DAY)
        .copy(note = "frost", reviewDueAtEpochMillis = 12 * DAY)
    private val liminal = word("w3", hole, "liminal", "The liminal field behind the house", at = 9 * DAY)
    private val list = MyWordsList(listOf(ironmongery, rime, liminal))

    @Test
    fun `words come newest first`() {
        assertEquals(listOf("w3", "w2", "w1"), list.words.map(SavedWord::id))
        assertEquals(listOf("w3", "w2", "w1"), list.shown(query = "", bookId = null).map(SavedWord::id))
    }

    @Test
    fun `a search finds the word, its sentence, its book and its note, case aside`() {
        assertEquals(listOf("w1"), list.shown("IRONmong", null).map(SavedWord::id))
        assertEquals(listOf("w3"), list.shown("behind the house", null).map(SavedWord::id))
        assertEquals(listOf("w2", "w1"), list.shown("christmas", null).map(SavedWord::id))
        assertEquals(listOf("w2"), list.shown("  frost ", null).map(SavedWord::id))
        assertTrue(list.shown("zebra", null).isEmpty())
    }

    @Test
    fun `the book filter shows one book's words and the search works inside it`() {
        assertEquals(listOf("w2", "w1"), list.shown("", carol.itemId).map(SavedWord::id))
        assertEquals(listOf("w2"), list.shown("rime", carol.itemId).map(SavedWord::id))
        assertTrue(list.shown("liminal", carol.itemId).isEmpty())
    }

    @Test
    fun `the books are listed by title with their word counts`() {
        assertEquals(listOf(MyWordsBook(carol, 2), MyWordsBook(hole, 1)), list.books)
        assertEquals(MyWordsBook(hole, 1), list.book(hole.itemId))
        assertNull(list.book("gone"))
        assertNull(list.book(null))
    }

    @Test
    fun `due counts words never reviewed and those whose day has come, within the book filter`() {
        assertEquals(2, list.dueCount(now))
        assertEquals(1, list.dueCount(now, carol.itemId))
        assertEquals(3, list.dueCount(12 * DAY))
        assertEquals("Review · 2 due", MyWordsCopy.review(list.dueCount(now)))
        assertEquals("Review", MyWordsCopy.review(0))
    }

    @Test
    fun `the summary counts words and books, or the book shown`() {
        assertEquals("3 words from 2 books", MyWordsCopy.summary(list, null))
        assertEquals("2 words from A Christmas Carol", MyWordsCopy.summary(list, list.book(carol.itemId)))
        assertTrue(MyWordsList(emptyList()).isEmpty)
    }

    @Test
    fun `a word's place is its page, else how far through the book, else nothing`() {
        val paged = ironmongery.copy(place = place("""{"href":"s1.xhtml","locations":{"position":11,"progression":0.2}}""", 0.04))
        val unpaged = ironmongery.copy(place = place("""{"href":"s1.xhtml","locations":{"progression":0.2}}""", 0.437))
        val broken = ironmongery.copy(place = place("not json", null))
        assertEquals("p. 11", wordPlace(paged))
        assertEquals("44%", wordPlace(unpaged))
        assertNull(wordPlace(broken))
        assertNull(wordPlace(ironmongery.copy(place = null)))
    }

    @Test
    fun `the word is picked out as a whole word in its sentence, else wherever it appears`() {
        val sentence = "Scrooge's name was good upon 'Change, for anything he chose to put his hand to."
        assertEquals(sentence.indexOf("hand"), wordSpan(sentence, "Hand")?.first)
        assertEquals(sentence.indexOf("Change"), wordSpan(sentence, "change")?.first)
        // The whole word "any", not the start of "anything".
        assertEquals(13..15, wordSpan("The anything any", "any"))
        assertEquals(4, wordSpan("The anything", "any")?.first)
        assertNull(wordSpan("A frosty morning", "rime"))
        assertNull(wordSpan("A frosty morning", " "))
    }

    @Test
    fun `a long sentence is cut around the word between words, so the word always shows`() {
        val long = "and then hurried on, Alice started to her feet, for it flashed across her mind that she had never " +
            "before seen a rabbit with either a waistcoat-pocket, or a watch to take out of it, and burning with " +
            "curiosity, she ran across the field after it"
        val shown = sentenceAround(long, "curiosity", before = 40, after = 20)
        assertEquals("…to take out of it, and burning with curiosity, she ran across…", shown)
        assertEquals("A frosty rime was on his head", sentenceAround("A frosty  rime was\non his head", "rime"))
        assertEquals("No word here.", sentenceAround("No word here.", "rime"))
    }

    @Test
    fun `my words export lists every book's words under it, by title, with sentences and notes`() {
        val text = MyWordsMarkdown.words(list.words, LocalDate.of(2026, 11, 20))
        assertEquals(
            """
            # My words

            Exported from Katana on 20 November 2026: 3 saved words.

            ## A Christmas Carol
            *Charles Dickens*

            - **ironmongery**: "the deadliest piece of ironmongery in the trade"
            - **rime**: "A frosty rime was on his head"
              Note: frost

            ## The Hole
            *Hiroko Oyamada*

            - **liminal**: "The liminal field behind the house"

            """.trimIndent(),
            text,
        )
        assertEquals("My words 2026-11-20.md", MyWordsMarkdown.fileName(LocalDate.of(2026, 11, 20)))
    }

    @Test
    fun `a word's own meaning comes first, the source fills in the others, none from the no-op source`() = runBlocking {
        val source = object : WordMeanings {
            override suspend fun meaning(word: String, language: String): String? =
                "Frost formed from\n freezing fog.".takeIf { word == "rime" || word == "ironmongery" }
        }
        val asked = MyWordsList(listOf(ironmongery, rime.copy(note = null))).words
        assertEquals(
            mapOf("w2" to "Frost formed from freezing fog.", "w1" to "Frost formed from freezing fog."),
            source.meaningsOf(asked, language = "en"),
        )
        // rime keeps its own meaning (its note), so the source isn't asked for it.
        assertEquals(mapOf("w1" to "Frost formed from freezing fog."), source.meaningsOf(list.words, language = "en"))
        assertTrue(WordMeanings.None.meaningsOf(list.words, language = "en").isEmpty())
        assertEquals("frost", meaningLine(rime, mapOf("w2" to "from the source")))
        assertEquals("from the source", meaningLine(ironmongery, mapOf("w1" to "from the source")))
        assertNull(meaningLine(ironmongery, emptyMap()))
    }

    @Test
    fun `review opens over the list and Back returns to it`() {
        val state = MyWordsState()
        assertFalse(state.back())
        state.round = WordReviewRound.of(listOf(rime))
        state.openReview()
        assertEquals(MyWordsPage.Review, state.page)
        assertNull(state.round)
        assertTrue(state.back())
        assertEquals(MyWordsPage.Words, state.page)
    }

    private fun place(json: String, progression: Double?) = AnnotationPlace("", json, "Stave One", progression)

    private fun word(id: String, book: AnnotatedBook, word: String, sentence: String, at: Long) = SavedWord(
        id = id,
        book = book,
        word = word,
        sentence = sentence,
        place = null,
        note = null,
        createdAtEpochMillis = at,
    )

    private companion object {
        const val DAY = 24L * 60L * 60L * 1000L
    }
}
