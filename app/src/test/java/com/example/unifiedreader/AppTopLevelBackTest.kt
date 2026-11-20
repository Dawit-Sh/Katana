package com.example.unifiedreader

import com.example.unifiedreader.core.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppTopLevelBackTest {
    @Test
    fun `back from the library has nowhere to go`() {
        assertNull(topLevelBackTarget(TopLevelDestination.Library))
    }

    @Test
    fun `back from every screen the menu opens goes to the library`() {
        listOf(
            TopLevelDestination.Lists,
            TopLevelDestination.Bookmarks,
            TopLevelDestination.MyWords,
            TopLevelDestination.Journal,
            TopLevelDestination.Settings,
        ).forEach { screen ->
            assertEquals(screen.name, TopLevelDestination.Library, topLevelBackTarget(screen))
        }
    }

    @Test
    fun `back from settings returns to the library of the server that was showing`() {
        val viewModel = createViewModel()
        viewModel.openLibrarySource(SourceType.GRIMMORY)
        viewModel.onTopLevelSelected(TopLevelDestination.Settings)

        viewModel.onTopLevelSelected(topLevelBackTarget(viewModel.uiState.topLevel)!!)

        assertEquals(TopLevelDestination.Library, viewModel.uiState.topLevel)
        assertEquals(SourceType.GRIMMORY, viewModel.uiState.selectedSource)
        clearViewModel(viewModel)
    }

    @Test
    fun `back from lists returns to the library it was opened over`() {
        val viewModel = createViewModel()
        viewModel.openLibrarySource(SourceType.STUMP)
        viewModel.onTopLevelSelected(TopLevelDestination.Lists)

        viewModel.onTopLevelSelected(topLevelBackTarget(viewModel.uiState.topLevel)!!)

        assertEquals(TopLevelDestination.Library, viewModel.uiState.topLevel)
        assertEquals(SourceType.STUMP, viewModel.uiState.selectedSource)
        assertNull(viewModel.uiState.activeReader)
        clearViewModel(viewModel)
    }
}
