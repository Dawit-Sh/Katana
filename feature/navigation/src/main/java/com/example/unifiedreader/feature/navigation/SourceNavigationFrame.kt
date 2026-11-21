package com.example.unifiedreader.feature.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.ui.components.appSystemBars

/**
 * The app's navigation around [content]: the rail beside it where [sourceNavigationUsesRail],
 * the drawer over it everywhere else. The content keeps its place in the tree either way, so a
 * window that crosses the breakpoint keeps its screens' state.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourceNavigationFrame(
    drawerState: DrawerState,
    sourceOrder: List<SourceType>,
    selectedDestination: SourceNavigationDestination,
    onDestinationSelected: (SourceNavigationDestination) -> Unit,
    /** Something waiting in Settings, such as the version of a waiting update. */
    settingsBadge: String? = null,
    /** "Add a server", while a server is not connected. */
    showAddSource: Boolean = false,
    /** The drawer's reading card (the rail has no room for one). */
    readingCard: (@Composable () -> Unit)? = null,
    /** The drawer's word of the day, at its bottom; null while there is none. */
    wordCard: (@Composable () -> Unit)? = null,
    /** How many words are saved, for My words in the drawer; null leaves My words out. */
    myWordsCount: Int? = null,
    /** How many books each source holds, at the end of its row in the drawer. */
    sourceCounts: Map<SourceType, Int> = emptyMap(),
    /** Katana's mark before its name in the drawer. */
    logo: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val railShown = sourceNavigationUsesRail()
    // A drawer left open closes when the window grows a rail.
    LaunchedEffect(railShown) {
        if (railShown && drawerState.currentValue != DrawerValue.Closed) drawerState.snapTo(DrawerValue.Closed)
    }
    SourceNavigationDrawer(
        drawerState = drawerState,
        sourceOrder = sourceOrder,
        selectedDestination = selectedDestination,
        onDestinationSelected = onDestinationSelected,
        settingsBadge = settingsBadge,
        showAddSource = showAddSource,
        gesturesEnabled = !railShown,
        readingCard = readingCard,
        wordCard = wordCard,
        myWordsCount = myWordsCount,
        sourceCounts = sourceCounts,
        logo = logo,
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (railShown) {
                SourceNavigationRail(
                    sourceOrder = sourceOrder,
                    selectedDestination = selectedDestination,
                    onDestinationSelected = onDestinationSelected,
                    settingsBadge = settingsBadge,
                    showAddSource = showAddSource,
                    showMyWords = myWordsCount != null,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // The rail already keeps clear of the start edge's cutout and bars.
                    .then(
                        if (railShown) {
                            Modifier.consumeWindowInsets(WindowInsets.appSystemBars.only(WindowInsetsSides.Start))
                        } else {
                            Modifier
                        },
                    ),
            ) {
                content()
            }
        }
    }
}
