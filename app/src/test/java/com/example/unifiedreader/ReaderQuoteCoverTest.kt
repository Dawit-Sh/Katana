package com.example.unifiedreader

import com.example.unifiedreader.feature.library.LibraryCoverImageVariant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderQuoteCoverTest {
    @Test
    fun `the book page's large cover comes first when it loads`() = runBlocking {
        val tried = mutableListOf<String>()
        val cover = firstQuoteCover(urlFor = { variant -> "cover-${variant.name}" }) { url -> url.also(tried::add) }
        assertEquals("cover-Detailed", cover)
        assertEquals(listOf("cover-Detailed"), tried)
    }

    @Test
    fun `offline, the library grid's saved cover stands in for the large one`() = runBlocking {
        // Only the grid's size is saved on the device; the larger ones have no address or don't load.
        val urls = mapOf(LibraryCoverImageVariant.GridLarge to "not-saved", LibraryCoverImageVariant.Grid to "saved")
        val cover = firstQuoteCover(urlFor = { variant -> urls[variant] }) { url -> url.takeIf { it == "saved" } }
        assertEquals("saved", cover)
    }

    @Test
    fun `a book with no cover gives none`() = runBlocking {
        assertNull(firstQuoteCover(urlFor = { "" }) { url -> url })
        assertNull(firstQuoteCover<String>(urlFor = { null }) { url -> url })
    }
}
