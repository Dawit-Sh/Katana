package com.example.unifiedreader.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.core.model.formatStorageSize
import com.example.unifiedreader.ui.components.AppDialog
import com.example.unifiedreader.ui.components.AppDialogConfirmButton
import com.example.unifiedreader.ui.components.AppDialogDismissButton
import com.example.unifiedreader.ui.components.AppLayout
import com.example.unifiedreader.ui.components.AppSettingsGroup
import com.example.unifiedreader.ui.components.AppTonalButton

/**
 * Settings › Storage: what Katana keeps on the tablet (downloads, books kept from opening,
 * covers) as one bar with its legend, then Manage books, Remove finished books and Clear covers.
 */
@Composable
internal fun StorageSettingsGroup(settings: StorageSettings) {
    var confirmFinished by remember { mutableStateOf(false) }
    var confirmCovers by remember { mutableStateOf(false) }
    AppSettingsGroup(label = "Storage") {
        StoragePanel(
            settings = settings,
            onRemoveFinished = { confirmFinished = true },
            onClearCovers = { confirmCovers = true },
        )
    }
    if (confirmFinished) {
        StorageConfirmDialog(
            title = StorageCopy.removeFinishedTitle(settings.state.finishedBookCount),
            body = StorageCopy.RemoveFinishedBody,
            confirm = "Remove",
            onConfirm = settings.onRemoveFinishedBooks,
            onDismiss = { confirmFinished = false },
        )
    }
    if (confirmCovers) {
        StorageConfirmDialog(
            title = "Clear covers?",
            body = StorageCopy.ClearCoversBody,
            confirm = "Clear",
            onConfirm = settings.onClearCovers,
            onDismiss = { confirmCovers = false },
        )
    }
}

/** The headline with what is free, the bar and its legend, then the buttons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StoragePanel(
    settings: StorageSettings,
    onRemoveFinished: () -> Unit,
    onClearCovers: () -> Unit,
) {
    val state = settings.state
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(AppLayout.inset),
        verticalArrangement = Arrangement.spacedBy(StorageGap),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = storageHeadline(state),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            storageFreeLabel(state)?.let { free ->
                Text(
                    text = free,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val parts = storageParts(state)
        val colors = storagePartColors()
        StorageBar(parts = parts, colors = colors, totalBytes = state.totalBytes)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(LegendGap),
            verticalArrangement = Arrangement.spacedBy(StorageButtonGap),
        ) {
            parts.forEachIndexed { index, part ->
                StorageLegend(label = "${part.label} ${formatStorageSize(part.bytes)}", color = colors[index])
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(StorageButtonGap),
            verticalArrangement = Arrangement.spacedBy(StorageButtonGap),
        ) {
            settings.onManageBooks?.let { manage ->
                AppTonalButton(onClick = manage) { Text("Manage books") }
            }
            AppTonalButton(onClick = onRemoveFinished, enabled = state.finishedBookCount > 0) {
                Text("Remove finished books")
            }
            AppTonalButton(onClick = onClearCovers, enabled = state.coverBytes > 0L) { Text("Clear covers") }
        }
    }
}

@Composable
private fun storagePartColors(): List<Color> {
    val scheme = MaterialTheme.colorScheme
    return listOf(scheme.primary, scheme.tertiary, scheme.secondary)
}

/** One bar split by [parts], each as wide as its share of [totalBytes]; empty, it is all track. */
@Composable
private fun StorageBar(parts: List<StoragePart>, colors: List<Color>, totalBytes: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(StorageBarHeight)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clearAndSetSemantics { },
    ) {
        if (totalBytes <= 0L) return@Row
        parts.forEachIndexed { index, part ->
            if (part.bytes > 0L) {
                Box(
                    modifier = Modifier
                        .weight(part.bytes.toFloat() / totalBytes)
                        .fillMaxHeight()
                        .background(colors[index]),
                )
            }
        }
    }
}

@Composable
private fun StorageLegend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LegendSwatchGap)) {
        Box(modifier = Modifier.size(LegendSwatch).clip(CircleShape).background(color))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StorageConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            AppDialogConfirmButton(
                text = confirm,
                destructive = true,
                onClick = {
                    onDismiss()
                    onConfirm()
                },
            )
        },
        dismissButton = { AppDialogDismissButton(text = "Cancel", onClick = onDismiss) },
    )
}

internal object StorageCopy {
    fun removeFinishedTitle(count: Int): String =
        if (count == 1) "Remove 1 finished book?" else "Remove $count finished books?"

    const val RemoveFinishedBody = "Their files leave this device. Your place, highlights and notes stay, " +
        "and the books stay in your library to open again from the server."
    const val ClearCoversBody = "Covers are fetched again from your servers as you scroll the library."
}

private val StorageGap = 12.dp
private val StorageButtonGap = 8.dp
private val StorageBarHeight = 10.dp
private val LegendGap = 18.dp
private val LegendSwatch = 10.dp
private val LegendSwatchGap = 6.dp
