package com.example.unifiedreader.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.unifiedreader.feature.reader.preferences.ReaderComfortPreferencesStore
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppSettingsRow
import com.example.unifiedreader.ui.components.AppSettingsRowDivider
import com.example.unifiedreader.ui.components.AppSwitch

/** The readers' light and keys settings, shared with the reader's Light card; null in previews and tests. */
val LocalReaderComfortPreferences = staticCompositionLocalOf<ReaderComfortPreferencesStore?> { null }

/**
 * "Keep the screen on" and "Volume keys turn pages" in the hub's Reading group, after the rows
 * already there (so it starts with a divider). The reader's Light card has the first one too.
 */
@Composable
internal fun ReaderComfortSettingsRows() {
    val store = LocalReaderComfortPreferences.current ?: return
    val comfort by store.comfortFlow.collectAsStateWithLifecycle()
    AppSettingsRowDivider()
    AppSettingsRow(
        title = ReaderComfortSettingsCopy.KeepScreenOn,
        icon = AppIcons.TabletAndroid,
        supporting = ReaderComfortSettingsCopy.KeepScreenOnSupporting,
        onClick = { store.update { it.copy(keepScreenOn = !it.keepScreenOn) } },
        showChevron = false,
        trailing = { AppSwitch(checked = comfort.keepScreenOn, onCheckedChange = null) },
    )
    AppSettingsRowDivider()
    AppSettingsRow(
        title = ReaderComfortSettingsCopy.VolumeKeys,
        icon = AppIcons.VolumeUp,
        supporting = ReaderComfortSettingsCopy.VolumeKeysSupporting,
        onClick = { store.update { it.copy(volumeKeysTurnPages = !it.volumeKeysTurnPages) } },
        showChevron = false,
        trailing = { AppSwitch(checked = comfort.volumeKeysTurnPages, onCheckedChange = null) },
    )
}

internal object ReaderComfortSettingsCopy {
    const val KeepScreenOn = "Keep the screen on"
    const val KeepScreenOnSupporting = "While a book is open, until 10 minutes without a touch"
    const val VolumeKeys = "Volume keys turn pages"
    const val VolumeKeysSupporting = "Down for the next page, up to go back. Mirrored in right-to-left books"
}
