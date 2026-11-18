package com.example.unifiedreader.feature.journal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.ui.components.AppLayout
import com.example.unifiedreader.ui.theme.UnifiedReaderThemeTokens

// The reading card's last seven days: their total and average, and a bar for each day.

@Composable
internal fun LastSevenDays(model: ReadingCardModel) {
    val most = model.lastSeven.maxOf { it.minutes }.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(TileLineGap)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = ReadingCardCopy.lastSeven(model.weekMinutes),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = ReadingCardCopy.average(model.averageMinutes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            // As tall as its tallest day: the time, the bar and the day's name.
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
            verticalAlignment = Alignment.Bottom,
        ) {
            model.lastSeven.forEach { day -> DayBar(day = day, most = most, modifier = Modifier.weight(1f)) }
        }
    }
}

/** One of the last seven days: its time, a bar as tall as its share of the most, and its name. */
@Composable
private fun DayBar(day: ReadingCardDay, most: Int, modifier: Modifier = Modifier) {
    val color = when {
        day.isToday -> MaterialTheme.colorScheme.primary
        day.minutes == 0 -> tileColor()
        else -> MaterialTheme.colorScheme.primary.copy(alpha = BarAlpha).compositeOver(tileColor())
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = ReadingCardCopy.minutes(day.minutes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.height(TileLineGap))
        Box(
            modifier = Modifier
                .width(BarWidth)
                .height(MinBar + (MaxBar - MinBar) * (day.minutes.toFloat() / most))
                .background(color, RoundedCornerShape(UnifiedReaderThemeTokens.presentation.chipCornerRadius)),
        )
        Spacer(Modifier.height(TileLineGap))
        Text(
            text = if (day.isToday) ReadingCardCopy.Today else ReadingCardCopy.weekday(day.date),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Medium,
            color = if (day.isToday) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

private const val BarAlpha = 0.6f
private val BarWidth: Dp = 22.dp
private val MinBar: Dp = 4.dp
private val MaxBar: Dp = 20.dp
