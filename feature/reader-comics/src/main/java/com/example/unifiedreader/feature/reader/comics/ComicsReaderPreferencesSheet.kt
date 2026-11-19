package com.example.unifiedreader.feature.reader.comics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.feature.reader.core.ReaderChromeDefaults
import com.example.unifiedreader.feature.reader.core.ReaderPageNumberChoiceRow
import com.example.unifiedreader.feature.reader.core.ReaderPanel
import com.example.unifiedreader.feature.reader.core.ReaderPanelSlot
import com.example.unifiedreader.feature.reader.core.curl.PageTurnStyle
import com.example.unifiedreader.feature.reader.preferences.ComicReaderMode
import com.example.unifiedreader.feature.reader.preferences.ComicReadingDirection
import com.example.unifiedreader.feature.reader.preferences.ReaderPagesLabel
import com.example.unifiedreader.ui.components.AppChoiceRow
import com.example.unifiedreader.ui.components.AppFloatingPanelPlacement
import com.example.unifiedreader.ui.components.AppPanelHeader

/**
 * The comics quick settings, a card under the settings button (a bottom sheet on phones): Pages (Turn or
 * Scroll) and Direction first, as in every reader, then the turn-pages rows, which stay in place but
 * disabled while scrolling so the card never changes height, then the page number.
 */
@Composable
internal fun ComicsReaderPreferencesSheet(
    isOpen: Boolean,
    slot: ReaderPanelSlot,
    readerState: ComicsReaderState,
    pageNumbersVisible: Boolean,
    onDismissRequest: () -> Unit,
    onPageNumbersVisibleChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // A card as tall as its content, at the top of the space between the bars.
    ReaderPanel(
        visible = isOpen,
        placement = AppFloatingPanelPlacement.TopEnd,
        width = ReaderChromeDefaults.settingsWidth,
        slot = slot,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        testTag = ComicsPreferencesSheetTag,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
        ) {
            // No subtitle: the reader's bar already names the book.
            AppPanelHeader(
                title = "Reading settings",
                onClose = onDismissRequest,
            )
            ComicPagesAndDirectionRows(readerState)
            ComicTurnPagesRows(readerState)
            ReaderPageNumberChoiceRow(
                visible = pageNumbersVisible,
                onVisibleChanged = onPageNumbersVisibleChanged,
            )
        }
    }
}

/**
 * Pages, Turn or Scroll, then Direction: left to right or right to left when turning, and also top to
 * bottom (webtoons) when scrolling, where left and right run the strip sideways.
 */
@Composable
private fun ComicPagesAndDirectionRows(readerState: ComicsReaderState) {
    val mode = readerState.readerMode
    AppChoiceRow(
        label = ReaderPagesLabel,
        options = ComicReaderMode.entries,
        selected = mode,
        onSelected = readerState::onReaderModeChanged,
        optionLabel = { it.label },
    )
    val choices = comicDirectionChoices(mode)
    AppChoiceRow(
        label = "Direction",
        options = choices,
        selected = comicDirectionChoice(mode, readerState.readingDirection, readerState.scrollAxis),
        onSelected = { choice ->
            val pick = comicDirectionPick(choice, mode)
            pick.axis?.let(readerState::onScrollAxisChanged)
            pick.direction?.let(readerState::onReadingDirectionChanged)
        },
        optionLabel = { it.label },
        // Three choices don't fit beside the label: the control takes the full width below it, also with
        // two, so the card keeps its height when Pages changes.
        stacked = true,
    )
}

/** What only applies to turning pages: two pages side by side, the page turn and the fit. */
@Composable
private fun ComicTurnPagesRows(readerState: ComicsReaderState) {
    val paged = readerState.readerMode == ComicReaderMode.Paged
    AppChoiceRow(
        label = "Two pages",
        options = ComicSpreadMode.entries,
        selected = readerState.spreadMode,
        onSelected = readerState::onSpreadModeChanged,
        optionLabel = ::comicSpreadLabel,
        enabled = paged,
    )
    AppChoiceRow(
        label = "Page turn",
        options = PageTurnStyle.entries,
        selected = readerState.pageTurnStyle,
        onSelected = readerState::onPageTurnStyleChanged,
        optionLabel = { it.label },
        enabled = paged,
    )
    AppChoiceRow(
        label = "Fit",
        options = ComicPageFitMode.entries,
        selected = readerState.pageFitMode,
        onSelected = readerState::onPageFitModeChanged,
        optionLabel = ::comicPageFitLabel,
        enabled = paged,
    )
}

/** Words instead of "LTR"/"RTL": the row's label already says it is the direction. */
internal fun comicReadingDirectionLabel(direction: ComicReadingDirection): String = direction.wordsLabel

/** Short segment labels; the row's label "Fit" carries the verb. */
internal fun comicPageFitLabel(fitMode: ComicPageFitMode): String = when (fitMode) {
    ComicPageFitMode.FitPage -> "Screen"
    ComicPageFitMode.FitWidth -> "Width"
    ComicPageFitMode.FitHeight -> "Height"
}

internal const val ComicsPreferencesSheetTag = "comics-preferences-sheet"
