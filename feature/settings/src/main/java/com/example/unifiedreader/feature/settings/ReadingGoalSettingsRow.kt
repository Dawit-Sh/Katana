package com.example.unifiedreader.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.unifiedreader.feature.reader.preferences.ReadingGoalStore
import com.example.unifiedreader.journal.ReadingGoal
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppMenu
import com.example.unifiedreader.ui.components.AppMenuChoiceItem
import com.example.unifiedreader.ui.components.AppMenuHeading
import com.example.unifiedreader.ui.components.AppSettingsRow
import com.example.unifiedreader.ui.components.AppSettingsRowDivider

/** The daily reading goal, shared with the side menu and the Journal; null in previews and tests. */
val LocalReadingGoalStore = staticCompositionLocalOf<ReadingGoalStore?> { null }

/**
 * "Daily reading goal" in the hub's Reading group: the minutes a day that colour the reading days in
 * the side menu and the Journal. A tap opens the choices.
 */
@Composable
internal fun ReadingGoalSettingsRow(leadingDivider: Boolean) {
    val store = LocalReadingGoalStore.current ?: return
    val goal by store.goalFlow.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf(false) }
    if (leadingDivider) AppSettingsRowDivider()
    Box {
        AppSettingsRow(
            title = ReadingGoalSettingsCopy.Title,
            icon = AppIcons.EventAvailable,
            supporting = ReadingGoalSettingsCopy.Supporting,
            value = ReadingGoalSettingsCopy.value(goal),
            onClick = { choosing = true },
            showChevron = false,
        )
        AppMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
            AppMenuHeading(text = ReadingGoalSettingsCopy.Title)
            ReadingGoal.Choices.forEach { minutes ->
                AppMenuChoiceItem(
                    text = ReadingGoal.label(minutes),
                    selected = minutes == goal,
                    onClick = {
                        store.setGoal(minutes)
                        choosing = false
                    },
                )
            }
        }
    }
}

internal object ReadingGoalSettingsCopy {
    const val Title = "Daily reading goal"
    const val Supporting = "Colours your reading days in the side menu and the Journal"

    /** "30 min": the row's title already says a day. */
    fun value(minutes: Int): String = "$minutes min"
}
