package com.example.unifiedreader.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.example.unifiedreader.core.model.LibraryFilterPanel
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.core.model.displayName
import com.example.unifiedreader.core.model.resolveLibraryFilterPanels
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppSettingsGroup
import com.example.unifiedreader.ui.components.AppSettingsRow
import com.example.unifiedreader.ui.components.AppSettingsRowDivider

/** Settings › Library: Filter chips, Tags (when the library gives them) and Source order, each its own page. */
@Composable
internal fun LibrarySettingsPage(
    sourceOrder: List<SourceType>,
    filterPanelsBySource: Map<SourceType, List<LibraryFilterPanel>>,
    tagsSummary: String?,
    selectedPage: SettingsPage?,
    onOpenPage: (SettingsPage) -> Unit,
    contentTopInset: Dp,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
) {
    SettingsPageContainer(
        contentTopInset = contentTopInset,
        modifier = modifier,
        horizontalPadding = horizontalPadding,
    ) {
        AppSettingsGroup {
            AppSettingsRow(
                title = SettingsPage.FilterChips.title,
                icon = AppIcons.FilterList,
                supporting = filterChipsSummary(sourceOrder, filterPanelsBySource),
                selected = selectedPage == SettingsPage.FilterChips,
                onClick = { onOpenPage(SettingsPage.FilterChips) },
            )
            if (tagsSummary != null) {
                AppSettingsRowDivider()
                AppSettingsRow(
                    title = SettingsPage.Tags.title,
                    icon = AppIcons.Sell,
                    supporting = tagsSummary,
                    selected = selectedPage == SettingsPage.Tags,
                    onClick = { onOpenPage(SettingsPage.Tags) },
                )
            }
            AppSettingsRowDivider()
            AppSettingsRow(
                title = SettingsPage.DrawerOrder.title,
                icon = AppIcons.SwapVert,
                supporting = drawerOrderSummary(sourceOrder),
                selected = selectedPage == SettingsPage.DrawerOrder,
                onClick = { onOpenPage(SettingsPage.DrawerOrder) },
            )
        }
    }
}

/**
 * The library row's line on the hub, for the first source in the order: "14 filters in Grimmory ·
 * Grimmory first".
 */
internal fun librarySettingsSummary(
    sourceOrder: List<SourceType>,
    filterPanelsBySource: Map<SourceType, List<LibraryFilterPanel>>,
): String {
    val first = sourceOrder.firstOrNull() ?: SourceType.entries.first()
    val count = resolveLibraryFilterPanels(sourceType = first, preferredPanels = filterPanelsBySource[first]).size
    val filters = if (count == 1) "1 filter" else "$count filters"
    return "$filters in ${first.displayName()} · ${first.displayName()} first"
}
