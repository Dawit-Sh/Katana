package com.example.unifiedreader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KatanaDeskMovesTest {
    private fun moves(onAnswered: () -> Unit = {}) = KatanaDeskMoves(
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("moves-test", Context.MODE_PRIVATE)
            .also { it.edit().clear().commit() },
        onAnswered,
    )

    @Test
    fun `up to five books just move, more are asked about once`() {
        assertEquals(true, katanaDeskMovesNow(5, null))
        assertNull(katanaDeskMovesNow(6, null))
        assertEquals(false, katanaDeskMovesNow(7, false))
        val moves = moves()
        assertTrue(moves.decide(toThisDevice = true, listOf("1", "2")))
        val seven = (1..7).map(Int::toString)
        assertFalse(moves.decide(toThisDevice = true, seven))
        assertEquals("Your PC is reading 7 books that aren't here. Bring all 7?", moves.question.value?.text)
        moves.answer(yes = true)
        assertNull(moves.question.value)
        assertTrue(moves.decide(toThisDevice = true, seven))
    }

    @Test
    fun `not now lets those books be, so the question doesn't come back`() {
        var synced = 0
        val moves = moves { synced++ }
        val seven = (1..7).map { "item-$it" }
        assertFalse(moves.decide(toThisDevice = false, seven))
        assertEquals("This device is reading 7 books that aren't on your PC. Send all 7?", moves.question.value?.text)
        moves.answer(yes = false)
        assertEquals(seven.toSet(), moves.settled(toThisDevice = false))
        assertFalse(moves.decide(toThisDevice = false, seven))
        assertNull(moves.question.value)
        assertEquals(1, synced)
        assertEquals(1, moves.tried("b"))
        assertEquals(2, moves.tried("b"))
    }
}
