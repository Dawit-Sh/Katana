package com.example.unifiedreader.feature.navigation

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class DrawerPanelWidthTest {
    @Test
    fun `the Fire 7 and wide phones keep the full drawer`() {
        assertEquals(360.dp, drawerPanelMaxWidth(hasReadingCard = true, windowWidth = 600.dp))
        assertEquals(320.dp, drawerPanelMaxWidth(hasReadingCard = false, windowWidth = 600.dp))
        assertEquals(360.dp, drawerPanelMaxWidth(hasReadingCard = true, windowWidth = 412.dp))
    }

    @Test
    fun `a 360 dp phone keeps 24 dp of the page beside the drawer`() {
        assertEquals(324.dp, drawerPanelMaxWidth(hasReadingCard = true, windowWidth = 360.dp))
        assertEquals(348.dp, drawerPanelMaxWidth(hasReadingCard = true, windowWidth = 384.dp))
    }

    @Test
    fun `a phone on its side keeps the full width for its rows side by side`() {
        assertEquals(360.dp, drawerPanelMaxWidth(hasReadingCard = false, windowWidth = 853.dp, sideBySide = true))
        assertEquals(324.dp, drawerPanelMaxWidth(hasReadingCard = false, windowWidth = 360.dp, sideBySide = true))
    }

    @Test
    fun `the reading card stays in the side menu at every text size Android offers`() {
        assertEquals(true, drawerShowsReadingCard(fontScale = 1f))
        assertEquals(true, drawerShowsReadingCard(fontScale = 1.3f))
        // At 200% the menu stood half empty without it; the fit shrinks it or drops it when height runs out.
        assertEquals(true, drawerShowsReadingCard(fontScale = 2f))
        assertEquals(false, drawerShowsReadingCard(fontScale = 2.5f))
    }
}
