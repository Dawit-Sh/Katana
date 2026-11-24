package com.example.unifiedreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KatanaDeskWordsTest {
    private fun word(text: String, sentence: String = "It was cold.", at: Long = 10L, book: String = "7") =
        KatanaDeskWord(word = text, deskBookId = book, sentence = sentence, note = null, createdAt = at, updatedAt = at)

    @Test
    fun newWordsGoAcrossBothWays() {
        val plan = planKatanaDeskWords(here = listOf(word("frost")), desk = listOf(word("ember")), base = emptyMap())

        assertEquals(listOf("frost"), plan.send.map { it.word })
        assertEquals(listOf("ember"), plan.take.map { it.word })
        assertEquals(setOf("ember|7"), plan.base.keys)
    }

    @Test
    fun aWordTheDeskDidNotTakeIsNeverRemovedHere() {
        val first = planKatanaDeskWords(here = listOf(word("frost")), desk = emptyList(), base = emptyMap())
        val next = planKatanaDeskWords(here = listOf(word("frost")), desk = emptyList(), base = first.base)

        assertTrue(next.removeHere.isEmpty())
        assertEquals(listOf("frost"), next.send.map { it.word })
    }

    @Test
    fun theSameWordInTheSameBookIsNeverDoubled() {
        val plan = planKatanaDeskWords(here = listOf(word("Frost")), desk = listOf(word("frost")), base = emptyMap())

        assertTrue(plan.send.isEmpty() && plan.take.isEmpty())
        assertEquals(setOf("frost|7"), plan.base.keys)
    }

    @Test
    fun aWordInAnotherBookIsAnotherWord() {
        val plan = planKatanaDeskWords(here = listOf(word("frost")), desk = listOf(word("frost", book = "8")), base = emptyMap())

        assertEquals(1, plan.send.size)
        assertEquals(1, plan.take.size)
    }

    @Test
    fun aRemovalCarriesAcross() {
        val before = word("frost")
        val base = mapOf(before.key to before.look, word("ember").key to word("ember").look)

        // frost removed here, ember removed on the desk
        val plan = planKatanaDeskWords(here = listOf(word("ember")), desk = listOf(before), base = base)

        assertEquals(listOf("frost"), plan.removeThere.map { it.word })
        assertEquals(listOf("ember"), plan.removeHere.map { it.word })
        assertTrue(plan.base.isEmpty())
    }

    @Test
    fun theSideThatChangedItWins() {
        val before = word("frost")
        val base = mapOf(before.key to before.look)

        val fromDesk = planKatanaDeskWords(listOf(before), listOf(word("frost", "Changed on the PC.", at = 5L)), base)
        val fromHere = planKatanaDeskWords(listOf(word("frost", "Changed here.", at = 5L)), listOf(before.copy(updatedAt = 99L)), base)

        assertEquals("Changed on the PC.", fromDesk.take.single().sentence)
        assertEquals("Changed here.", fromHere.send.single().sentence)
    }

    @Test
    fun whenBothChangedTheNewerWins() {
        val plan = planKatanaDeskWords(
            here = listOf(word("frost", "Older.", at = 10L)),
            desk = listOf(word("frost", "Newer.", at = 20L)),
            base = mapOf(word("frost").key to word("frost").look),
        )

        assertEquals("Newer.", plan.take.single().sentence)
        assertEquals("Newer.\u0001", plan.base.getValue("frost|7"))
    }

    @Test
    fun theDesksWordsAreReadAndSentBack() {
        val body = """{"words":[{"word":"ember","bookId":7,"bookTitle":"Winter","bookAuthor":null,"sentence":"An ember.",""" +
            """"chapter":null,"fraction":0.5,"note":null,"createdAt":3,"updatedAt":4}]}"""

        val parsed = parseKatanaDeskWords(body).single()

        assertEquals(word("ember", "An ember.", at = 3L).copy(updatedAt = 4L, fraction = 0.5, bookTitle = "Winter"), parsed)
        assertTrue(katanaDeskWordsBody(listOf(parsed), emptyList()).contains("\"bookId\":7"))
    }
}
