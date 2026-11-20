package com.example.unifiedreader.feature.reader.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppLayout

/**
 * A strip over the dock's row for stepping through places in the book one by one (the marks
 * opened from a list): an [icon], what is there now ([title]) over where it is among them
 * ([position], "3 of 12"), then Previous, Next and Close (`navigate_before`, `navigate_next`,
 * `close`, named [previousLabel], [nextLabel] and [closeLabel]), as the EPUB search's strip has them.
 */
@Composable
fun ReaderStepStrip(
    icon: ImageVector,
    title: String,
    position: String,
    previousLabel: String,
    nextLabel: String,
    closeLabel: String,
    onStep: (forward: Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = readerOverlayTokens()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppLayout.headingGap)
            .testTag(ReaderStepStripTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(ReaderStepStripInset),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tokens.colors.secondaryText,
                modifier = Modifier.size(ReaderStepStripIconSize),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.colors.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = position,
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.colors.secondaryText,
                    maxLines = 1,
                )
            }
        }
        ReaderBarButton(icon = AppIcons.NavigateBefore, contentDescription = previousLabel, onClick = { onStep(false) })
        ReaderBarButton(icon = AppIcons.NavigateNext, contentDescription = nextLabel, onClick = { onStep(true) })
        ReaderBarButton(icon = AppIcons.Close, contentDescription = closeLabel, onClick = onClose)
    }
}

/**
 * [ReaderStepStrip] for a dock's strip slot, or null while there is nothing to step through ([title]
 * or [position] null).
 */
@Composable
fun readerStepDockStrip(
    icon: ImageVector,
    title: String?,
    position: String?,
    previousLabel: String,
    nextLabel: String,
    closeLabel: String,
    onStep: (forward: Boolean) -> Unit,
    onClose: () -> Unit,
): (@Composable () -> Unit)? {
    if (title == null || position == null) return null
    return { ReaderStepStrip(icon, title, position, previousLabel, nextLabel, closeLabel, onStep, onClose) }
}

/** Two strips for one dock slot, [first] over [second]; null when both are. */
fun stackedDockStrips(
    first: (@Composable () -> Unit)?,
    second: (@Composable () -> Unit)?,
): (@Composable () -> Unit)? = when {
    first == null -> second
    second == null -> first
    else -> {
        {
            Column(verticalArrangement = Arrangement.spacedBy(AppLayout.headingGap)) {
                first()
                second()
            }
        }
    }
}

const val ReaderStepStripTag = "reader-step-strip"

// The same insets and icon as the EPUB search's strip.
private val ReaderStepStripInset = 10.dp
private val ReaderStepStripIconSize = 20.dp
