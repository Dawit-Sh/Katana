package com.example.unifiedreader.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.core.model.LibraryFilterPanel
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.core.model.resolveLibraryFilterPanels
import com.example.unifiedreader.core.model.displayName
import com.example.unifiedreader.feature.dictionary.DictionarySettingsRow
import com.example.unifiedreader.feature.dictionary.LocalDictionary
import com.example.unifiedreader.feature.navigation.navigationIcon
import com.example.unifiedreader.feature.translation.LocalTranslationService
import com.example.unifiedreader.feature.translation.translationHubSummary
import com.example.unifiedreader.feature.updates.AppUpdateActions
import com.example.unifiedreader.feature.updates.AppUpdateAutoCheckRow
import com.example.unifiedreader.feature.updates.AppUpdateState
import com.example.unifiedreader.feature.updates.AppUpdateVersionRow
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppSettingsDefaults
import com.example.unifiedreader.ui.components.AppSettingsGroup
import com.example.unifiedreader.ui.components.AppSettingsRow
import com.example.unifiedreader.ui.components.AppSettingsRowDivider
import com.example.unifiedreader.ui.components.appGutter

/** The settings front page: grouped rows that each say their current value and open a page. */
@Composable
internal fun SettingsHub(
    themeMode: ThemeMode,
    sources: List<SourceSettingsEntry>,
    sourceOrder: List<SourceType>,
    filterPanelsBySource: Map<SourceType, List<LibraryFilterPanel>>,
    syncSettings: SyncSettingsUiState,
    versionName: String,
    updateState: AppUpdateState,
    updateActions: AppUpdateActions,
    selectedPage: SettingsPage?,
    onOpenPage: (SettingsPage) -> Unit,
    contentTopInset: Dp,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = appGutter(),
    tools: SettingsTools = SettingsTools(),
) {
    SettingsPageContainer(
        contentTopInset = contentTopInset,
        modifier = modifier,
        horizontalPadding = horizontalPadding,
    ) {
        AppSettingsGroup(label = "Appearance") {
            AppSettingsRow(
                title = "Theme",
                icon = AppIcons.Palette,
                supporting = themeMode.label,
                selected = selectedPage == SettingsPage.Theme,
                onClick = { onOpenPage(SettingsPage.Theme) },
                // With very large text the little preview would squeeze "Theme" until it breaks mid-word; it goes.
                trailing = if (showsThemePreview(LocalDensity.current.fontScale)) {
                    { ThemeCoverPreview() }
                } else {
                    null
                },
            )
        }
        ReadingSettingsGroup(
            newBookLook = tools.newBookLook,
            selectedPage = selectedPage,
            onOpenPage = onOpenPage,
        )
        AppSettingsGroup(label = "Sources") {
            sources.forEachIndexed { index, entry ->
                if (index > 0) AppSettingsRowDivider()
                val page = SettingsPage.Source(entry.sourceType)
                val status = remoteSourceHubStatus(entry.connection, entry.connectivity, entry.lastSyncedAtEpochMillis)
                AppSettingsRow(
                    title = entry.sourceType.displayName(),
                    icon = entry.sourceType.navigationIcon(),
                    supporting = status.label,
                    supportingTone = status.tone,
                    selected = selectedPage == page,
                    onClick = { onOpenPage(page) },
                )
                // Katana Desk, the PC on this Wi-Fi, sits under Grimmory: it answers as Grimmory does.
                if (entry.sourceType == SourceType.GRIMMORY) tools.katanaDesk?.let { desk -> KatanaDeskRow(desk) }
            }
            if (sources.none { it.sourceType == SourceType.GRIMMORY }) {
                tools.katanaDesk?.let { desk -> KatanaDeskRow(desk, divider = sources.isNotEmpty()) }
            }
            // What waits to upload belongs with the servers it goes to.
            if (sources.isNotEmpty()) AppSettingsRowDivider()
            AppSettingsRow(
                title = SettingsPage.SyncStatus.title,
                icon = AppIcons.Refresh,
                supporting = syncSettings.summaryLabel(),
                supportingColor = if (syncSettings.failedCount > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    Color.Unspecified
                },
                selected = selectedPage == SettingsPage.SyncStatus,
                onClick = { onOpenPage(SettingsPage.SyncStatus) },
            )
        }
        AppSettingsGroup(label = "Library") {
            AppSettingsRow(
                title = SettingsPage.Library.title,
                icon = AppIcons.Tune,
                supporting = librarySettingsSummary(sourceOrder, filterPanelsBySource),
                selected = selectedPage == SettingsPage.Library || selectedPage?.parent == SettingsPage.Library,
                onClick = { onOpenPage(SettingsPage.Library) },
            )
        }
        tools.storage?.let { storage -> StorageSettingsGroup(storage) }
        tools.backup?.let { backup -> BackupSettingsGroup(backup) }
        AppSettingsGroup(label = "About") {
            AppUpdateVersionRow(
                versionName = versionName,
                state = updateState,
                actions = updateActions,
            )
            AppSettingsRowDivider()
            AppUpdateAutoCheckRow(
                state = updateState,
                onCheckedChange = updateActions.onAutoCheckChanged,
            )
            AppSettingsRowDivider(inset = AppSettingsDefaults.dividerInset)
            AppSettingsRow(
                title = SettingsPage.Licenses.title,
                icon = AppIcons.Description,
                selected = selectedPage == SettingsPage.Licenses || selectedPage?.parent == SettingsPage.Licenses,
                onClick = { onOpenPage(SettingsPage.Licenses) },
            )
        }
    }
}

/**
 * Settings › Reading: the look new books open with, Translation when the app has it, the English
 * dictionary (download, remove), the readers' Keep the screen on and Volume keys switches, then the
 * daily reading goal. Left out when it has none of them.
 */
@Composable
private fun ReadingSettingsGroup(
    newBookLook: NewBookLookSettings?,
    selectedPage: SettingsPage?,
    onOpenPage: (SettingsPage) -> Unit,
) {
    val translation = LocalTranslationService.current
    val comfort = LocalReaderComfortPreferences.current
    val dictionary = LocalDictionary.current
    val readingGoal = LocalReadingGoalStore.current
    if (listOf(newBookLook, translation, dictionary, comfort, readingGoal).all { it == null }) return
    AppSettingsGroup(label = "Reading") {
        if (newBookLook != null) {
            AppSettingsRow(
                title = SettingsPage.NewBookLook.title,
                icon = AppIcons.FormatSize,
                supporting = newBookLookSummary(newBookLook.description),
                selected = selectedPage == SettingsPage.NewBookLook,
                onClick = { onOpenPage(SettingsPage.NewBookLook) },
            )
        }
        if (translation != null) {
            if (newBookLook != null) AppSettingsRowDivider()
            LaunchedEffect(translation) { translation.start() }
            val translationState by translation.state.collectAsStateWithLifecycle()
            AppSettingsRow(
                title = SettingsPage.Translation.title,
                icon = AppIcons.Translate,
                supporting = translationHubSummary(translationState),
                selected = selectedPage == SettingsPage.Translation,
                onClick = { onOpenPage(SettingsPage.Translation) },
            )
        }
        // A built-in dictionary has nothing to download or remove, so it has no row here.
        val dictionaryRow = dictionary?.takeUnless { it.isBuiltIn }
        if (dictionaryRow != null) {
            if (newBookLook != null || translation != null) AppSettingsRowDivider()
            DictionarySettingsRow(dictionary = dictionaryRow)
        }
        ReaderComfortSettingsRows(leadingDivider = newBookLook != null || translation != null || dictionaryRow != null)
        ReadingGoalSettingsRow(leadingDivider = listOf(newBookLook, translation, dictionaryRow, comfort).any { it != null })
    }
}

/**
 * Three small covers drawn in the current theme's colours, so the Theme row shows what the
 * library will look like: a sun over stripes, a glow, a framed title page.
 */
@Composable
private fun ThemeCoverPreview() {
    val scheme = MaterialTheme.colorScheme
    val frame = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .padding(end = 4.dp)
            .clip(frame)
            .background(scheme.background)
            .border(1.dp, scheme.outlineVariant, frame)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        ThemeCovers(
            card = scheme.surfaceContainerHighest,
            accent = scheme.primary,
            third = scheme.tertiary,
            frame = scheme.onTertiary.copy(alpha = 0.6f),
            coverSize = DpSize(18.dp, 27.dp),
            gap = 3.dp,
        )
    }
}

/** The Theme row's preview fits beside its words up to 150% text; past that the words need the room. */
internal fun showsThemePreview(fontScale: Float): Boolean = fontScale < LargeTextScale

private const val LargeTextScale = 1.5f

/** The order itself: "Kavita, Grimmory, Local". Launch restores where you were, so no source is "first to open". */
internal fun drawerOrderSummary(sourceOrder: List<SourceType>): String =
    sourceOrder.takeIf { it.isNotEmpty() }?.joinToString { it.displayName() } ?: "The order of your sources"

/** How many filters each library's sheet shows, in the sources' order: "8 in Kavita, 6 in Grimmory, 4 in Local". */
internal fun filterChipsSummary(
    sourceOrder: List<SourceType>,
    filterPanelsBySource: Map<SourceType, List<LibraryFilterPanel>>,
): String = sourceOrder.ifEmpty { SourceType.entries }.joinToString { sourceType ->
    val count = resolveLibraryFilterPanels(sourceType = sourceType, preferredPanels = filterPanelsBySource[sourceType]).size
    "$count in ${sourceType.displayName()}"
}

/** The Katana Desk row, after a divider when it follows another row. */
@Composable
private fun KatanaDeskRow(desk: KatanaDeskSourceRow, divider: Boolean = true) {
    if (divider) AppSettingsRowDivider()
    AppSettingsRow(
        title = "Katana Desk",
        icon = AppIcons.Computer,
        supporting = desk.supporting,
        supportingTone = desk.tone,
        onClick = desk.onOpen,
    )
}
