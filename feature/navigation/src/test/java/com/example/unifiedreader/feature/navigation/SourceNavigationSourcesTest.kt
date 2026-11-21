package com.example.unifiedreader.feature.navigation

import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceNavigationSourcesTest {
    private val order = listOf(SourceType.GRIMMORY, SourceType.KAVITA, SourceType.STUMP, SourceType.LOCAL)

    @Test
    fun `the rail lists connected servers and Local, in the source order`() {
        assertEquals(
            listOf(SourceType.GRIMMORY, SourceType.KAVITA, SourceType.LOCAL),
            navigationSources(order, connectedServers = setOf(SourceType.KAVITA, SourceType.GRIMMORY)),
        )
        assertEquals(listOf(SourceType.LOCAL), navigationSources(order, connectedServers = emptySet()))
    }

    @Test
    fun `add a server shows until all three are connected`() {
        assertTrue(canAddNavigationSource(setOf(SourceType.KAVITA, SourceType.GRIMMORY)))
        assertFalse(canAddNavigationSource(setOf(SourceType.KAVITA, SourceType.GRIMMORY, SourceType.STUMP)))
    }

    @Test
    fun `a loaded session decides, an unknown one falls back to the recorded set`() {
        val sessions = mapOf(SourceType.KAVITA to true, SourceType.STUMP to null, SourceType.GRIMMORY to false)

        assertEquals(setOf(SourceType.KAVITA), navigationConnectedServers(sessions, lastConnectedSources = setOf(SourceType.GRIMMORY)))
        assertEquals(
            setOf(SourceType.KAVITA, SourceType.STUMP),
            navigationConnectedServers(sessions, lastConnectedSources = setOf(SourceType.STUMP)),
        )
    }

    @Test
    fun `with nothing recorded, a server still loading counts as connected`() {
        val sessions = mapOf<SourceType, Boolean?>(SourceType.KAVITA to null, SourceType.STUMP to null, SourceType.GRIMMORY to false)

        assertEquals(setOf(SourceType.KAVITA, SourceType.STUMP), navigationConnectedServers(sessions, lastConnectedSources = null))
    }

    @Test
    fun `the set to record waits for every session`() {
        assertNull(knownConnectedServers(mapOf(SourceType.KAVITA to true, SourceType.STUMP to null, SourceType.GRIMMORY to false)))
        assertEquals(
            setOf(SourceType.KAVITA),
            knownConnectedServers(mapOf(SourceType.KAVITA to true, SourceType.STUMP to false, SourceType.GRIMMORY to false)),
        )
    }

    @Test
    fun `each loaded source counts its books, a source still loading shows none`() {
        fun book(id: String, source: SourceType) =
            ReadableItem(id = id, sourceType = source, libraryId = "1", title = id, format = FormatType.EPUB)
        val items = listOf(
            book("g1", SourceType.GRIMMORY),
            book("g2", SourceType.GRIMMORY),
            book("g3", SourceType.GRIMMORY),
            book("l1", SourceType.LOCAL),
        )
        assertEquals(
            mapOf(SourceType.GRIMMORY to 3, SourceType.LOCAL to 1),
            navigationSourceCounts(items, loadedSources = setOf(SourceType.GRIMMORY, SourceType.LOCAL)),
        )
        assertEquals(mapOf(SourceType.LOCAL to 1), navigationSourceCounts(items, loadedSources = setOf(SourceType.LOCAL)))
        assertEquals(mapOf(SourceType.LOCAL to 0), navigationSourceCounts(emptyList(), loadedSources = setOf(SourceType.LOCAL)))
        assertEquals(emptyMap<SourceType, Int>(), navigationSourceCounts(items, loadedSources = emptySet()))
    }
}
