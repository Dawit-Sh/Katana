package com.example.unifiedreader.feature.reader.pdf

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.unifiedreader.feature.reader.core.ReaderChromeDefaults
import com.example.unifiedreader.feature.reader.core.ReaderPageNumberChoiceRow
import com.example.unifiedreader.feature.reader.core.ReaderPanel
import com.example.unifiedreader.feature.reader.core.ReaderPanelSlot
import com.example.unifiedreader.feature.reader.preferences.ReaderPagesLabel
import com.example.unifiedreader.feature.reader.preferences.ReaderPagesMode
import com.example.unifiedreader.ui.components.AppChoiceRow
import com.example.unifiedreader.ui.components.AppFloatingPanelPlacement
import com.example.unifiedreader.ui.components.AppLayout
import com.example.unifiedreader.ui.components.AppPanelHeader

/** What the PDF reader's settings show and change. */
internal class PdfReaderSettings(
    val pagesMode: ReaderPagesMode,
    val pageNumbersVisible: Boolean,
    val onPagesModeChanged: (ReaderPagesMode) -> Unit,
    val onPageNumbersVisibleChanged: (Boolean) -> Unit,
)

/**
 * The PDF and DjVu reader's settings, a card under their button as in the comics reader (a bottom sheet on
 * phones): Pages, Turn or Scroll, then the page number.
 */
@Composable
internal fun PdfReaderSettingsPanel(
    isOpen: Boolean,
    slot: ReaderPanelSlot,
    settings: PdfReaderSettings,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ReaderPanel(
        visible = isOpen,
        placement = AppFloatingPanelPlacement.TopEnd,
        width = ReaderChromeDefaults.settingsWidth,
        slot = slot,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        testTag = PdfSettingsPanelTag,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = AppLayout.headingGap),
        ) {
            // No subtitle: the reader's bar already names the book.
            AppPanelHeader(title = "Reading settings", onClose = onDismissRequest)
            AppChoiceRow(
                label = ReaderPagesLabel,
                options = ReaderPagesMode.entries,
                selected = settings.pagesMode,
                onSelected = settings.onPagesModeChanged,
                optionLabel = { mode -> mode.label },
            )
            ReaderPageNumberChoiceRow(
                visible = settings.pageNumbersVisible,
                onVisibleChanged = settings.onPageNumbersVisibleChanged,
            )
        }
    }
}

internal const val PdfSettingsPanelTag = "pdf-settings-panel"
