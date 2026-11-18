package com.example.unifiedreader.feature.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerState
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.core.model.displayName
import com.example.unifiedreader.ui.components.AppDivider
import com.example.unifiedreader.ui.components.AppDrawerBadge
import com.example.unifiedreader.ui.components.AppDrawerItem
import com.example.unifiedreader.ui.components.AppGroupLabel
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppLayout
import com.example.unifiedreader.ui.components.appGlass
import com.example.unifiedreader.ui.theme.UnifiedReaderThemeTokens

sealed interface SourceNavigationDestination {
    data class Source(
        val sourceType: SourceType,
    ) : SourceNavigationDestination

    data object Settings : SourceNavigationDestination
    data object Journal : SourceNavigationDestination

    /** The user's lists and reading orders, from every source. */
    data object Lists : SourceNavigationDestination

    /** Every book's highlights, bookmarks and saved words. */
    data object Bookmarks : SourceNavigationDestination

    /** "Add a server": the choices the first launch offers. */
    data object AddSource : SourceNavigationDestination
}

@Composable
fun SourceNavigationDrawer(
    drawerState: DrawerState,
    sourceOrder: List<SourceType>,
    selectedDestination: SourceNavigationDestination,
    onDestinationSelected: (SourceNavigationDestination) -> Unit,
    /** Shown at the end of the Settings row, such as the version of a waiting update. */
    settingsBadge: String? = null,
    /** "Add a server" under the sources, while a server is not connected. */
    showAddSource: Boolean = false,
    /** False while the rail shows: then nothing opens the drawer, not even a swipe. */
    gesturesEnabled: Boolean = true,
    /** The reading card, in the room between the sources and the destinations below. */
    readingCard: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val presentation = UnifiedReaderThemeTokens.presentation
    val drawerShape = RoundedCornerShape(presentation.cardCornerRadius)
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = gesturesEnabled,
        drawerContent = {
            // A floating glass panel, inset from the screen edges and the system bars. Given the
            // drawer state, the sheet closes on back and follows the predictive back gesture.
            ModalDrawerSheet(
                drawerState = drawerState,
                modifier = Modifier
                    .windowInsetsPadding(
                        WindowInsets.systemBars.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
                    )
                    .padding(start = 12.dp, top = 12.dp, bottom = 12.dp)
                    // A little wider with the reading card, so its fifteen weeks keep a usable size.
                    .widthIn(max = if (readingCard != null) DrawerWithCardMaxWidth else DrawerMaxWidth)
                    .appGlass(shape = drawerShape),
                drawerShape = drawerShape,
                drawerContainerColor = Color.Transparent,
                windowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(horizontal = 12.dp, vertical = 16.dp),
                ) {
                    DrawerTopSection(readingCard = readingCard, modifier = Modifier.weight(1f)) {
                        KatanaDrawerHeader()
                        AppGroupLabel(
                            text = "Sources",
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                        )
                        sourceOrder.forEach { sourceType ->
                            SourceNavigationItem(
                                sourceType = sourceType,
                                selected = selectedDestination == SourceNavigationDestination.Source(sourceType),
                                onDestinationSelected = onDestinationSelected,
                            )
                        }
                        if (showAddSource) {
                            AppDrawerItem(
                                label = { Text(text = "Add a server") },
                                selected = false,
                                onClick = { onDestinationSelected(SourceNavigationDestination.AddSource) },
                                icon = {
                                    Icon(
                                        imageVector = AppIcons.Add,
                                        contentDescription = null,
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    AppDivider(modifier = Modifier.padding(vertical = 12.dp))
                    AppDrawerItem(
                        label = { Text(text = "Lists") },
                        selected = selectedDestination == SourceNavigationDestination.Lists,
                        onClick = { onDestinationSelected(SourceNavigationDestination.Lists) },
                        icon = {
                            Icon(
                                imageVector = AppIcons.Checklist,
                                contentDescription = null,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AppDrawerItem(
                        label = { Text(text = "Bookmarks") },
                        selected = selectedDestination == SourceNavigationDestination.Bookmarks,
                        onClick = { onDestinationSelected(SourceNavigationDestination.Bookmarks) },
                        icon = {
                            Icon(
                                imageVector = AppIcons.Bookmark,
                                contentDescription = null,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AppDrawerItem(
                        label = { Text(text = "Journal") },
                        selected = selectedDestination == SourceNavigationDestination.Journal,
                        onClick = { onDestinationSelected(SourceNavigationDestination.Journal) },
                        icon = {
                            Icon(
                                imageVector = AppIcons.History,
                                contentDescription = null,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AppDrawerItem(
                        label = { Text(text = "Settings") },
                        selected = selectedDestination == SourceNavigationDestination.Settings,
                        onClick = { onDestinationSelected(SourceNavigationDestination.Settings) },
                        icon = {
                            Icon(
                                imageVector = AppIcons.Settings,
                                contentDescription = null,
                            )
                        },
                        badge = settingsBadge?.let { badge ->
                            {
                                AppDrawerBadge(
                                    text = badge,
                                    selected = selectedDestination == SourceNavigationDestination.Settings,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        content = content,
    )
}

/**
 * The drawer's top part, scrolling when the window is short: the name and the sources and, with a
 * [readingCard], the card midway in the room left under them.
 */
@Composable
private fun DrawerTopSection(
    readingCard: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    sources: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight),
            verticalArrangement = if (readingCard != null) Arrangement.SpaceBetween else Arrangement.Top,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { sources() }
            if (readingCard != null) {
                Box(modifier = Modifier.padding(top = AppLayout.headingGap)) { readingCard() }
                // The foot of SpaceBetween, so the card sits midway rather than at the bottom.
                Spacer(Modifier)
            }
        }
    }
}

private val DrawerMaxWidth = 320.dp
private val DrawerWithCardMaxWidth = 360.dp

@Composable
private fun SourceNavigationItem(
    sourceType: SourceType,
    selected: Boolean,
    onDestinationSelected: (SourceNavigationDestination) -> Unit,
) {
    AppDrawerItem(
        label = { Text(text = sourceType.displayName()) },
        selected = selected,
        onClick = {
            onDestinationSelected(
                SourceNavigationDestination.Source(sourceType),
            )
        },
        icon = {
            Icon(
                imageVector = sourceType.navigationIcon(),
                contentDescription = null,
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun SourceType.navigationIcon(): ImageVector = when (this) {
    SourceType.KAVITA -> AppIcons.CollectionsBookmark
    SourceType.STUMP -> AppIcons.MenuBook
    SourceType.GRIMMORY -> AppIcons.Cloud
    SourceType.LOCAL -> AppIcons.Folder
}
