package com.example.unifiedreader

import com.example.unifiedreader.core.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceStartDecisionTest {
    private val noneSignedIn = mapOf(SourceType.KAVITA to false, SourceType.STUMP to false, SourceType.GRIMMORY to false)

    @Test
    fun `cleared data, nothing set up, opens on the chooser`() {
        assertEquals(true, noSourcesSetUp(noneSignedIn, localBooksKnown = true, hasLocalBooks = false))
    }

    @Test
    fun `a signed-in server or a book from this device never shows it, whenever the app was installed`() {
        val grimmory = noneSignedIn + (SourceType.GRIMMORY to true)
        assertEquals(false, noSourcesSetUp(grimmory, localBooksKnown = true, hasLocalBooks = false))
        assertEquals(false, noSourcesSetUp(noneSignedIn, localBooksKnown = true, hasLocalBooks = true))
        // Known as soon as one source is set up, even while the rest still load (the Fire with Grimmory).
        val loading = mapOf(SourceType.KAVITA to null, SourceType.STUMP to null, SourceType.GRIMMORY to true)
        assertEquals(false, noSourcesSetUp(loading, localBooksKnown = false, hasLocalBooks = false))
    }

    @Test
    fun `waits while a sign-in or the books from this device are still loading`() {
        val kavitaLoading = noneSignedIn + (SourceType.KAVITA to null)
        assertEquals(null, noSourcesSetUp(kavitaLoading, localBooksKnown = true, hasLocalBooks = false))
        assertEquals(null, noSourcesSetUp(noneSignedIn, localBooksKnown = false, hasLocalBooks = false))
    }
}
