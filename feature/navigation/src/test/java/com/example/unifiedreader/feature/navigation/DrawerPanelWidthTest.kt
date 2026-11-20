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
    fun `the reading card leaves the side menu only at very large text, where it would be cut off`() {
        assertEquals(true, drawerShowsReadingCard(fontScale = 1f))
        assertEquals(true, drawerShowsReadingCard(fontScale = 1.3f))
        assertEquals(false, drawerShowsReadingCard(fontScale = 2f))
    }
}
