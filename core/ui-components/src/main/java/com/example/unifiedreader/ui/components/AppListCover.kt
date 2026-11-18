package com.example.unifiedreader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.unifiedreader.ui.theme.UnifiedReaderThemeTokens

/**
 * A list's cover: the first four books' covers in a 2×2 mosaic at the cover's 2:3 and corner
 * radius, 2 dp apart, so a list reads apart from a book and from a series' stack. [cell] draws
 * cover [index] filling the modifier it is given. One to three books show the first cover whole
 * (a mosaic with empty cells reads as broken).
 *
 * An empty list is a dashed outline of the cover, a place waiting for books rather than a blank
 * one: with [emptyAction] ("Add books") a plus over those words, for a list you fill by hand;
 * else the list mark, when [emptyMark] (a row's 40 dp cover is only the outline).
 */
@Composable
fun AppListCover(
    count: Int,
    modifier: Modifier = Modifier,
    emptyAction: String? = null,
    emptyMark: Boolean = true,
    cell: @Composable (index: Int, modifier: Modifier) -> Unit,
) {
    val radius = UnifiedReaderThemeTokens.presentation.coverCornerRadius
    if (count == 0) {
        EmptyListCover(action = emptyAction, mark = emptyMark, radius = radius, modifier = modifier)
        return
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(radius))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        verticalArrangement = Arrangement.spacedBy(AppListCoverDefaults.gap),
    ) {
        if (count < AppListCoverDefaults.mosaicCount) {
            cell(0, Modifier.fillMaxSize())
        } else {
            repeat(2) { rowIndex ->
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppListCoverDefaults.gap),
                ) {
                    repeat(2) { columnIndex ->
                        val index = rowIndex * 2 + columnIndex
                        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                            if (index < count) cell(index, Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyListCover(
    action: String?,
    mark: Boolean,
    radius: Dp,
    modifier: Modifier = Modifier,
) {
    val content = MaterialTheme.colorScheme.onSurfaceVariant
    val outline = MaterialTheme.colorScheme.onSurface.copy(alpha = AppListCoverDefaults.emptyOutlineAlpha)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .dashedOutline(outline, radius),
        contentAlignment = Alignment.Center,
    ) {
        when {
            action != null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
            ) {
                Icon(
                    imageVector = AppIcons.Add,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(AppListCoverDefaults.emptyAddSize),
                )
                Text(
                    text = action,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                    fontWeight = FontWeight.SemiBold,
                    color = content,
                    textAlign = TextAlign.Center,
                )
            }
            mark -> Icon(
                imageVector = AppIcons.Checklist,
                contentDescription = null,
                tint = content.copy(alpha = AppListCoverDefaults.emptyMarkAlpha),
                modifier = Modifier.size(AppListCoverDefaults.emptyMarkSize),
            )
        }
    }
}

/** A dashed line just inside the bounds, at the cover's corner radius. */
private fun Modifier.dashedOutline(color: Color, radius: Dp): Modifier = drawBehind {
    val width = AppListCoverDefaults.emptyOutlineWidth.toPx()
    val half = width / 2
    val dash = AppListCoverDefaults.emptyOutlineDash.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(half, half),
        size = Size(size.width - width, size.height - width),
        cornerRadius = CornerRadius((radius.toPx() - half).coerceAtLeast(0f)),
        style = Stroke(width = width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
    )
}

object AppListCoverDefaults {
    val gap = 2.dp

    /** How many books a list needs for the mosaic; fewer show the first cover. */
    const val mosaicCount = 4

    /** The list mark in an empty list's outline. */
    val emptyMarkSize = 32.dp
    const val emptyMarkAlpha = 0.6f

    /** The plus over "Add books" in an empty list you fill by hand. */
    val emptyAddSize = 26.dp

    /** An empty list's dashed outline. */
    val emptyOutlineWidth = 2.dp
    val emptyOutlineDash = 6.dp
    const val emptyOutlineAlpha = 0.2f
}
