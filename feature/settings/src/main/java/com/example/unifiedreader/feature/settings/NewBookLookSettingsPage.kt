package com.example.unifiedreader.feature.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.example.unifiedreader.ui.components.AppSettingsGroup
import com.example.unifiedreader.ui.components.AppSettingsRow
import com.example.unifiedreader.ui.components.AppSettingsRowDivider
import com.example.unifiedreader.ui.components.AppTonalButton

/**
 * The look "Use these for new books" saved in a book's Aa panel, setting by setting, with Reset.
 * Without one, it says where to save it.
 */
@Composable
internal fun NewBookLookSettingsPage(
    settings: NewBookLookSettings,
    contentTopInset: Dp,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val description = settings.description
    SettingsPageContainer(
        contentTopInset = contentTopInset,
        modifier = modifier,
        horizontalPadding = horizontalPadding,
    ) {
        if (!description.isSaved) {
            SettingsPageLine(NewBookLookCopy.NotSaved)
            return@SettingsPageContainer
        }
        SettingsPageLine(NewBookLookCopy.Saved)
        AppSettingsGroup(label = "Text") {
            description.lines.forEachIndexed { index, line ->
                if (index > 0) AppSettingsRowDivider()
                AppSettingsRow(title = line.label, value = line.value)
            }
        }
        AppTonalButton(onClick = settings.onReset, modifier = Modifier.fillMaxWidth()) {
            Text(NewBookLookCopy.Reset)
        }
        SettingsPageLine(NewBookLookCopy.ResetNote)
    }
}

internal object NewBookLookCopy {
    const val Saved = "Books you haven't opened yet start with this look. A book you have changed keeps its own."
    const val NotSaved = "No look saved yet, so new books open with the reader's own font and spacing. " +
        "To save one, open a book, set the text in Aa, then tap Use these for new books."
    const val Reset = "Reset"
    const val ResetNote = "Reset forgets this look; new books then open with the reader's own font and spacing."
}
