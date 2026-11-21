package com.example.unifiedreader.feature.annotations

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordOfTheDayTest {
    private val book = AnnotatedBook("grimmory:item:1", "Jane Eyre", "Charlotte Brontë")
    private val words = listOf("desolate", "ironmongery", "rime", "liminal", "querulous", "sallow", "tremulous", "wan")
        .mapIndexed { index, word -> word(id = "w$index", word = word) }
    private val saturday = LocalDate.of(2026, 11, 21)

    @Test
    fun `the day's word is the same all day, whatever order the words come in`() {
        val first = WordOfTheDay.pick(words, saturday)
        assertEquals(first, WordOfTheDay.pick(words.reversed(), saturday))
        assertEquals(first, WordOfTheDay.pick(words.shuffled(), saturday))
    }

    @Test
    fun `other days pick other words`() {
        val picks = (0L until 14L).map { day -> WordOfTheDay.pick(words, saturday.plusDays(day))!!.id }.toSet()
        assertTrue("a fortnight of eight words picks more than one: $picks", picks.size > 1)
    }

    @Test
    fun `another word steps through the day's order and comes back round`() {
        val order = WordOfTheDay.order(words, saturday)
        assertEquals(order.first(), WordOfTheDay.pick(words, saturday))
        var current: SavedWord? = order.first()
        val seen = mutableListOf<String>()
        repeat(words.size) {
            current = WordOfTheDay.next(words, saturday, current)
            seen += current!!.id
        }
        assertEquals(order.drop(1).map(SavedWord::id) + order.first().id, seen)
    }

    @Test
    fun `a kept word stays for its day while it is saved`() {
        val kept = KeptWord(saturday, "w5")
        assertEquals("w5", WordOfTheDay.pick(words, saturday, kept)?.id)
        // Another day, or the word removed: the day's own pick again.
        assertEquals(WordOfTheDay.pick(words, saturday.plusDays(1)), WordOfTheDay.pick(words, saturday.plusDays(1), kept))
        assertEquals(WordOfTheDay.pick(words - words[5], saturday), WordOfTheDay.pick(words - words[5], saturday, kept))
    }

    @Test
    fun `no words, no word of the day, and one word stays itself`() {
        assertNull(WordOfTheDay.pick(emptyList(), saturday))
        assertNull(WordOfTheDay.next(emptyList(), saturday, null))
        assertEquals("w0", WordOfTheDay.next(words.take(1), saturday, words[0])?.id)
    }

    @Test
    fun `a word is new, learning or learned, and known today once answered today`() {
        val noon = saturday.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(WordProgress.New, words[0].progress())
        val learning = words[0].copy(reviewLevel = 1, reviewedAtEpochMillis = noon)
        assertEquals(WordProgress.Learning, learning.progress())
        val learned = words[0].copy(reviewLevel = WordReviewSchedule.maxLevel, reviewedAtEpochMillis = noon)
        assertEquals(WordProgress.Learned, learned.progress())
        assertTrue(learning.knownOn(saturday, ZoneOffset.UTC))
        assertFalse(learning.knownOn(saturday.plusDays(1), ZoneOffset.UTC))
        // Answered Again today: reviewed, but not known.
        assertFalse(words[0].copy(reviewLevel = 0, reviewedAtEpochMillis = noon).knownOn(saturday, ZoneOffset.UTC))
    }

    @Test
    fun `Review on the word of the day opens the review on that word alone`() {
        val state = MyWordsState()
        state.openReview(words[3])
        assertEquals(MyWordsPage.Review, state.page)
        assertEquals(listOf("w3"), state.round?.queue)
    }

    private fun word(id: String, word: String) = SavedWord(
        id = id,
        book = book,
        word = word,
        sentence = null,
        place = null,
        note = null,
        createdAtEpochMillis = 0L,
    )
}
