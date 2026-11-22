package com.example.unifiedreader

import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.feature.navigation.SourceNavigationState
import org.junit.Assert.assertEquals
import org.junit.Test

class RestoredLibrarySourceTest {
    private val order = listOf(SourceType.KAVITA, SourceType.STUMP, SourceType.GRIMMORY, SourceType.LOCAL)

    @Test
    fun `a device with only its own books opens on them, not on a server that isn't connected`() {
        val nothingConnected = SourceNavigationState(sourceOrder = order, lastConnectedSources = emptySet())
        assertEquals(SourceType.LOCAL, restoredLibrarySource(SourceType.KAVITA, nothingConnected))
    }

    @Test
    fun `the library left open comes back while its server is still connected`() {
        val grimmory = SourceNavigationState(sourceOrder = order, lastConnectedSources = setOf(SourceType.GRIMMORY))
        assertEquals(SourceType.GRIMMORY, restoredLibrarySource(SourceType.GRIMMORY, grimmory))
        assertEquals(SourceType.LOCAL, restoredLibrarySource(SourceType.LOCAL, grimmory))
        // Signed out of Kavita since: the first library the rail lists.
        assertEquals(SourceType.GRIMMORY, restoredLibrarySource(SourceType.KAVITA, grimmory))
    }

    @Test
    fun `before the servers were ever recorded, the library left open stays`() {
        val unknown = SourceNavigationState(sourceOrder = order, lastConnectedSources = null)
        assertEquals(SourceType.STUMP, restoredLibrarySource(SourceType.STUMP, unknown))
    }
}
