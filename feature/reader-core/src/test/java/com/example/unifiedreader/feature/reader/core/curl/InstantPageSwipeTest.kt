package com.example.unifiedreader.feature.reader.core.curl

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstantPageSwipeTest {
    private fun swipe(rightToLeft: Boolean = false) =
        InstantPageSwipe(rightToLeft = rightToLeft, touchSlop = 10f, longPressMillis = 500L)

    @Test
    fun leftSwipeTurnsToTheNextPageWhenTheFingerLifts() {
        val swipe = swipe()
        swipe.onEvent(Offset(-5f, 0f), elapsedMillis = 10L, pressedPointers = 1)
        assertFalse(swipe.owned)
        swipe.onEvent(Offset(-40f, 2f), elapsedMillis = 40L, pressedPointers = 1)
        assertTrue(swipe.owned)
        swipe.onEvent(Offset(-80f, 3f), elapsedMillis = 80L, pressedPointers = 0)
        assertEquals(PageCurlTarget.Next, swipe.turnOnRelease())
    }

    @Test
    fun aRightToLeftBookTurnsTheOtherWay() {
        val swipe = swipe(rightToLeft = true)
        swipe.onEvent(Offset(-40f, 0f), elapsedMillis = 40L, pressedPointers = 1)
        swipe.onEvent(Offset(-60f, 0f), elapsedMillis = 60L, pressedPointers = 0)
        assertEquals(PageCurlTarget.Previous, swipe.turnOnRelease())
    }

    @Test
    fun aSwipeBroughtBackPastItsStartTurnsNothing() {
        val swipe = swipe()
        swipe.onEvent(Offset(-40f, 0f), elapsedMillis = 40L, pressedPointers = 1)
        swipe.onEvent(Offset(30f, 0f), elapsedMillis = 90L, pressedPointers = 0)
        assertTrue(swipe.owned)
        assertNull(swipe.turnOnRelease())
    }

    @Test
    fun aDragUpOrDownStaysWithThePage() {
        val swipe = swipe()
        swipe.onEvent(Offset(4f, 40f), elapsedMillis = 40L, pressedPointers = 1)
        swipe.onEvent(Offset(-40f, 80f), elapsedMillis = 80L, pressedPointers = 0)
        assertFalse(swipe.owned)
        assertNull(swipe.turnOnRelease())
    }

    @Test
    fun aLongPressStaysWithThePageForSelectingWords() {
        val swipe = swipe()
        swipe.onEvent(Offset(2f, 0f), elapsedMillis = 600L, pressedPointers = 1)
        swipe.onEvent(Offset(-40f, 0f), elapsedMillis = 700L, pressedPointers = 1)
        assertFalse(swipe.owned)
    }

    @Test
    fun noPageThatWayLeavesTheSwipeToThePage() {
        val swipe = swipe()
        swipe.onEvent(Offset(-40f, 0f), elapsedMillis = 40L, pressedPointers = 1, canTurn = { false })
        assertFalse(swipe.owned)
        assertNull(swipe.turnOnRelease())
    }

    @Test
    fun aSecondFingerOrACancelDropsTheTurn() {
        val pinch = swipe()
        pinch.onEvent(Offset(-40f, 0f), elapsedMillis = 40L, pressedPointers = 1)
        pinch.onEvent(Offset(-50f, 0f), elapsedMillis = 50L, pressedPointers = 2)
        pinch.onEvent(Offset(-60f, 0f), elapsedMillis = 60L, pressedPointers = 0)
        assertTrue(pinch.owned)
        assertNull(pinch.turnOnRelease())

        val cancelled = swipe()
        cancelled.onEvent(Offset(-40f, 0f), elapsedMillis = 40L, pressedPointers = 1)
        cancelled.onEvent(Offset(-60f, 0f), elapsedMillis = 60L, pressedPointers = 0, cancelled = true)
        assertNull(cancelled.turnOnRelease())
    }

    @Test
    fun selectedTextKeepsTheSwipeWithThePage() {
        val swipe = InstantPageSwipe(rightToLeft = false, touchSlop = 10f, longPressMillis = 500L, selectingText = true)
        swipe.onEvent(Offset(-40f, 0f), elapsedMillis = 40L, pressedPointers = 1)
        assertFalse(swipe.owned)
    }
}
