package com.example.unifiedreader.feature.annotations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordReviewTest {
    private val day = 24L * 60 * 60 * 1000
    private val hole = AnnotatedBook("grimmory:item:2694", "The Hole", "Hiroko Oyamada")

    @Test
    fun `known words rest a day, then 3, 7, 14 and 30 days, and Again starts over at once`() {
        var level = 0
        val rests = (1..6).map {
            val step = WordReviewSchedule.next(level, known = true, atEpochMillis = 0)
            level = step.level
            step.dueAtEpochMillis / day
        }
        assertEquals(listOf(1L, 3L, 7L, 14L, 30L, 30L), rests)
        assertEquals(WordReviewStep(level = 0, dueAtEpochMillis = 500), WordReviewSchedule.next(4, known = false, atEpochMillis = 500))
    }

    @Test
    fun `a round takes the words that are due, never-reviewed and longest-waiting first`() {
        val fresh = word("fresh", due = null, made = 30)
        val older = word("older", due = null, made = 10)
        val overdue = word("overdue", due = 50, made = 5)
        val resting = word("resting", due = 500, made = 1)

        val due = WordReviewSchedule.round(listOf(fresh, resting, overdue, older), atEpochMillis = 100)

        assertEquals(listOf("older", "fresh", "overdue"), due.map(SavedWord::id))
        assertEquals(4, WordReviewSchedule.round(listOf(fresh, resting, overdue, older), atEpochMillis = 100, all = true).size)
        assertFalse(WordReviewSchedule.isDue(resting, atEpochMillis = 100))
    }

    @Test
    fun `Again sends a card to the back of the round, I know it takes it out`() {
        var round = WordReviewRound.of(listOf(word("a"), word("b"), word("c")))
        assertEquals("a", round.current)
        assertEquals(1, round.position)

        round = round.answer(known = false)
        assertEquals(listOf("b", "c", "a"), round.queue)
        assertEquals(1, round.position)

        round = round.answer(known = true).answer(known = true)
        assertEquals("a", round.current)
        assertEquals(3, round.position)

        round = round.answer(known = true)
        assertTrue(round.isFinished)
        assertNull(round.current)
        assertEquals(3, round.known)
        assertEquals(round, round.answer(known = true))
    }

    @Test
    fun `a single word answered Again stays in the round`() {
        val round = WordReviewRound.of(listOf(word("solar"))).answer(known = false)
        assertEquals("solar", round.current)
        assertFalse(round.isFinished)
    }

    private fun word(id: String, due: Long? = null, made: Long = 0) = SavedWord(
        id = id,
        book = hole,
        word = id,
        sentence = null,
        place = null,
        note = null,
        createdAtEpochMillis = made,
        reviewDueAtEpochMillis = due,
    )
}
