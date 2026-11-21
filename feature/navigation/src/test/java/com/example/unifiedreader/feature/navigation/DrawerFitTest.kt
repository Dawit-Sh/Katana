package com.example.unifiedreader.feature.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class DrawerFitTest {
    // A reading card 200 tall with its smallest heat map, 260 with its biggest; a word card 100 without
    // its meaning, 120 with it; 4 under the reading card and 12 over the word card.
    private val reading = CardHeights(least = 200, most = 260)
    private val word = CardHeights(least = 100, most = 120)

    private fun fit(room: Int, readingCard: CardHeights? = reading, wordCard: CardHeights? = word) =
        fitDrawer(room, readingCard, wordCard, readingGap = 4, wordGap = 12)

    @Test
    fun `with room for everything both cards show whole`() {
        assertEquals(DrawerFit(260, 120), fit(room = 396))
        assertEquals(DrawerFit(260, 120), fit(room = 900))
    }

    @Test
    fun `the heat map gets smaller first`() {
        assertEquals(DrawerFit(250, 120), fit(room = 386))
        assertEquals(DrawerFit(200, 120), fit(room = 336))
    }

    @Test
    fun `then the word card loses its meaning, the heat map taking what is left`() {
        assertEquals(DrawerFit(200, 100), fit(room = 335 - 19))
        assertEquals(DrawerFit(219, 100), fit(room = 335))
    }

    @Test
    fun `then the word card goes, and the heat map grows back into its room`() {
        assertEquals(DrawerFit(260, null), fit(room = 300))
        assertEquals(DrawerFit(211, null), fit(room = 215))
        assertEquals(DrawerFit(200, null), fit(room = 204))
    }

    @Test
    fun `last the reading card goes too`() {
        assertEquals(DrawerFit(null, null), fit(room = 203))
        assertEquals(DrawerFit(null, null), fit(room = -40))
    }

    @Test
    fun `without a reading card the word card has the room to itself`() {
        assertEquals(DrawerFit(null, 120), fit(room = 132, readingCard = null))
        assertEquals(DrawerFit(null, 100), fit(room = 131, readingCard = null))
        assertEquals(DrawerFit(null, null), fit(room = 111, readingCard = null))
    }

    @Test
    fun `without a word card the reading card takes all it can`() {
        assertEquals(DrawerFit(260, null), fit(room = 300, wordCard = null))
        assertEquals(DrawerFit(230, null), fit(room = 234, wordCard = null))
    }
}
