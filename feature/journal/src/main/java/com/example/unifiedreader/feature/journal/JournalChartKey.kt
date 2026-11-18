package com.example.unifiedreader.feature.journal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.ui.components.AppLayout

private val KeyDotSize = 8.dp

/** One colour of a chart and what it means. */
internal data class JournalChartKeyEntry(val color: Color, val label: String)

/** A chart's key under it: a dot in each colour with its meaning, "● Your usual hours  ● Other hours". */
@Composable
internal fun JournalChartKey(
    entries: List<JournalChartKeyEntry>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(AppLayout.menuInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        entries.forEach { entry ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(KeyDotSize)
                        .clip(CircleShape)
                        .background(entry.color),
                )
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
