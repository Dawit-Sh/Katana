package com.example.unifiedreader.feature.annotations

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.ui.components.AppAnchoredCard
import com.example.unifiedreader.ui.components.AppChip
import com.example.unifiedreader.ui.components.AppDialog
import com.example.unifiedreader.ui.components.AppDialogConfirmButton
import com.example.unifiedreader.ui.components.AppDialogDismissButton
import com.example.unifiedreader.ui.components.AppIconButton
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppLayout
import com.example.unifiedreader.ui.components.AppTextField

/**
 * The card on a highlight, at the highlight: its style and colour (a tap changes it at once), its
 * note, Copy, Share as an image and Delete. Placed in a full-size slot over the page; the anchor
 * comes in window pixels. The share card opens from it, in a dialog of its own. The same in every
 * reader that keeps highlights (EPUB, PDF).
 */
@Composable
fun HighlightCardOverlay(
    editor: HighlightEditor,
    highlights: List<ReaderHighlight>,
    modifier: Modifier = Modifier,
) {
    editor.sharing.highlightId?.let { sharingId ->
        highlights.firstOrNull { it.id == sharingId }?.let { highlight ->
            QuoteShareDialog(highlight = highlight, onDismiss = editor.sharing::close)
        }
    }
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(modifier = Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() })
    val state = editor.card ?: return
    val highlight = highlights.firstOrNull { it.id == state.highlightId } ?: return
    AppAnchoredCard(
        anchor = state.anchor?.translate(-origin.x, -origin.y),
        onDismissRequest = editor::closeCard,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(AppLayout.menuInset),
            verticalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
        ) {
            HighlightStyleChips(highlight = highlight, onStyle = { style -> editor.restyle(highlight.id, style = style) })
            HighlightColorRow(highlight = highlight, onColor = { color -> editor.restyle(highlight.id, color = color) })
            highlight.note?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = NotePreviewLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HighlightCardActions(highlight = highlight, editor = editor)
        }
    }
}

/** The four styles; they wrap to a second row on a narrow card, so all stay in view. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HighlightStyleChips(highlight: ReaderHighlight, onStyle: (HighlightStyle) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
        verticalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
    ) {
        HighlightStyle.entries.forEach { style ->
            AppChip(
                selected = highlight.style == style,
                onClick = { onStyle(style) },
                label = { Text(HighlightCopy.style(style)) },
            )
        }
    }
}

@Composable
private fun HighlightColorRow(highlight: ReaderHighlight, onColor: (HighlightColor) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
    ) {
        HighlightColor.entries.forEach { color ->
            HighlightColorSwatch(color = color, selected = highlight.color == color, onClick = { onColor(color) })
        }
    }
}

/** Note, Copy, Share as an image and Delete, at the card's end. */
@Composable
private fun HighlightCardActions(highlight: ReaderHighlight, editor: HighlightEditor) {
    val context = LocalContext.current
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        AppIconButton(onClick = { editor.editNote(highlight.id) }) {
            Icon(
                imageVector = AppIcons.Edit,
                contentDescription = if (highlight.note == null) HighlightCopy.AddNote else HighlightCopy.EditNote,
            )
        }
        AppIconButton(
            onClick = {
                context.copyToClipboard(highlight.text)
                editor.closeCard()
            },
        ) {
            Icon(imageVector = AppIcons.ContentCopy, contentDescription = HighlightCopy.Copy)
        }
        AppIconButton(
            onClick = {
                editor.closeCard()
                editor.sharing.open(highlight.id)
            },
        ) {
            Icon(imageVector = AppIcons.Share, contentDescription = HighlightCopy.ShareImage)
        }
        AppIconButton(onClick = { editor.delete(highlight) }) {
            Icon(imageVector = AppIcons.Delete, contentDescription = HighlightCopy.Delete)
        }
    }
}

/** The note editor for a highlight, as a dialog over the book. */
@Composable
fun HighlightNoteDialog(
    editor: HighlightEditor,
    highlights: List<ReaderHighlight>,
    modifier: Modifier = Modifier,
) {
    val highlightId = editor.editingNoteOf ?: return
    val highlight = highlights.firstOrNull { it.id == highlightId } ?: return
    var text by rememberSaveable(highlightId) { mutableStateOf(highlight.note.orEmpty()) }
    AppDialog(
        onDismissRequest = editor::cancelNote,
        modifier = modifier,
        title = { Text(HighlightCopy.NoteTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppLayout.headingGap)) {
                Text(
                    text = "“${highlight.text}”",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = QuotePreviewLines,
                    overflow = TextOverflow.Ellipsis,
                )
                AppTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(HighlightCopy.NotePlaceholder) },
                    minLines = NoteFieldLines,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        dismissButton = { AppDialogDismissButton(text = HighlightCopy.Cancel, onClick = editor::cancelNote) },
        confirmButton = { AppDialogConfirmButton(text = HighlightCopy.Save, onClick = { editor.saveNote(highlightId, text) }) },
    )
}

@Composable
private fun HighlightColorSwatch(
    color: HighlightColor,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val ring = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .size(SwatchSize)
            .clip(CircleShape)
            .background(Color(color.argb))
            .then(if (selected) Modifier.border(SwatchRing, ring, CircleShape) else Modifier)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = HighlightCopy.color(color)
                this.selected = selected
            },
    )
}

private const val NotePreviewLines = 3
private const val QuotePreviewLines = 4
private const val NoteFieldLines = 3
private val SwatchSize = 32.dp
private val SwatchRing = 2.dp
