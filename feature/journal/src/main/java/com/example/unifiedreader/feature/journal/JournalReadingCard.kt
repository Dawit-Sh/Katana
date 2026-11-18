package com.example.unifiedreader.feature.journal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.example.unifiedreader.journal.JournalSnapshot
import com.example.unifiedreader.ui.components.AppDivider
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppLayout
import com.example.unifiedreader.ui.components.AppPanel
import com.example.unifiedreader.ui.theme.UnifiedReaderThemeTokens
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The side menu's reading card, from the journal's own reading sessions: a heat map of the last
 * 15 weeks, the streak, the days read and the books finished, the best day, and bars for the last
 * seven days. A tap opens the Journal.
 */
@Composable
fun JournalReadingCard(
    snapshot: JournalSnapshot,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
) {
    val model by produceState<ReadingCardModel?>(null, snapshot.events, snapshot.sessions, today) {
        value = withContext(Dispatchers.Default) { readingCardModel(snapshot, today) }
    }
    val shown = model ?: return
    AppPanel(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = ReadingCardCopy.description(shown) },
    ) {
        Column(
            modifier = Modifier.padding(AppLayout.menuInset),
            verticalArrangement = Arrangement.spacedBy(AppLayout.menuInset),
        ) {
            CardHeader(shown)
            StatTiles(shown)
            HeatMap(shown)
            Legend(shown)
            AppDivider()
            LastSevenDays(shown)
        }
    }
}

@Composable
private fun CardHeader(model: ReadingCardModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = ReadingCardCopy.Title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                text = ReadingCardCopy.range(model.firstDay),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = ReadingCardCopy.Journal,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Icon(
            imageVector = AppIcons.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(SmallIcon),
        )
    }
}

@Composable
private fun StatTiles(model: ReadingCardModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(AppLayout.headingGap)) {
        StatTile(
            value = model.streak.toString(),
            unit = ReadingCardCopy.days(model.streak),
            label = ReadingCardCopy.Streak,
            icon = AppIcons.LocalFireDepartment,
            tint = StreakTint,
            modifier = Modifier.weight(1f),
        )
        StatTile(
            value = model.daysRead.toString(),
            unit = null,
            label = ReadingCardCopy.DaysRead,
            icon = AppIcons.EventAvailable,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        StatTile(
            value = model.finished.toString(),
            unit = null,
            label = ReadingCardCopy.Finished,
            icon = AppIcons.TaskAlt,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatTile(
    value: String,
    unit: String?,
    label: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(tileColor(), RoundedCornerShape(UnifiedReaderThemeTokens.presentation.innerCornerRadius))
            .padding(horizontal = AppLayout.headingGap + TileExtraInset, vertical = AppLayout.headingGap),
        verticalArrangement = Arrangement.spacedBy(TileLineGap),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            unit?.let {
                Text(
                    text = " $it",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = TileUnitNudge),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TileLineGap)) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(TinyIcon))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** The weeks as columns of Monday to Sunday, the months over them and every other weekday beside them. */
@Composable
private fun HeatMap(model: ReadingCardModel) {
    val shades = heatShades()
    val todayRing = MaterialTheme.colorScheme.onSurface
    val futureEdge = MaterialTheme.colorScheme.outlineVariant
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().clearAndSetSemantics { }) {
        val columns = model.weeks.size
        val cell = min(MaxCell, (maxWidth - DayLabelWidth - AppLayout.headingGap - CellGap * (columns - 1)) / columns)
        val step = cell + CellGap
        Column(verticalArrangement = Arrangement.spacedBy(TileLineGap)) {
            MonthLabels(labels = model.monthLabels, step = step)
            Row(horizontalArrangement = Arrangement.spacedBy(AppLayout.headingGap)) {
                Column(modifier = Modifier.width(DayLabelWidth), verticalArrangement = Arrangement.spacedBy(CellGap)) {
                    WeekdayLabels.forEachIndexed { index, label ->
                        Box(modifier = Modifier.height(cell), contentAlignment = Alignment.CenterStart) {
                            if (index % 2 == 0) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Canvas(modifier = Modifier.width(step * columns - CellGap).height(step * DaysInWeek - CellGap)) {
                    val size = cell.toPx()
                    val gap = step.toPx()
                    val radius = CornerRadius(size * CellRounding)
                    model.weeks.forEachIndexed { x, week ->
                        week.forEachIndexed { y, day ->
                            val topLeft = Offset(x * gap, y * gap)
                            val tile = Size(size, size)
                            when {
                                day.isFuture -> drawRoundRect(futureEdge, topLeft, tile, radius, style = Stroke(1.dp.toPx()))
                                else -> drawRoundRect(shades[day.level], topLeft, tile, radius)
                            }
                            if (day.isToday) {
                                val ring = 2.dp.toPx()
                                drawRoundRect(
                                    todayRing,
                                    Offset(topLeft.x - ring / 2, topLeft.y - ring / 2),
                                    Size(size + ring, size + ring),
                                    radius,
                                    style = Stroke(ring),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The months' names over the columns that hold their first day, [step] apart a column. */
@Composable
private fun MonthLabels(labels: List<Pair<Int, String>>, step: Dp) {
    Box(modifier = Modifier.padding(start = DayLabelWidth + AppLayout.headingGap).height(MonthRowHeight).fillMaxWidth()) {
        labels.forEach { (column, name) ->
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.offset(x = step * column),
            )
        }
    }
}

@Composable
private fun Legend(model: ReadingCardModel) {
    val shades = heatShades()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TileLineGap)) {
        Text(
            text = model.bestDay?.let(ReadingCardCopy::bestDay).orEmpty(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Text(text = ReadingCardCopy.Less, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        shades.forEach { shade ->
            Box(
                modifier = Modifier
                    .size(LegendSwatch)
                    .background(shade, RoundedCornerShape(UnifiedReaderThemeTokens.presentation.chipCornerRadius)),
            )
        }
        Text(text = ReadingCardCopy.More, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The five tiles from no reading to the most: the empty tile, then the accent ever stronger. */
@Composable
private fun heatShades(): List<Color> {
    val ground = tileColor()
    val accent = MaterialTheme.colorScheme.primary
    return remember(ground, accent) { listOf(ground) + HeatAlphas.map { alpha -> accent.copy(alpha = alpha).compositeOver(ground) } }
}

@Composable
internal fun tileColor(): Color = MaterialTheme.colorScheme.surfaceContainerHighest

/** What the reading card says. */
internal object ReadingCardCopy {
    const val Title = "Reading"
    const val Journal = "Journal"
    const val Streak = "Streak"
    const val DaysRead = "Days read"
    const val Finished = "Finished"
    const val Less = "Less"
    const val More = "More"
    const val Today = "Today"

    // Day first, as in Britain, with three-letter months ("Sep"; the JDK's UK months say "Sept").
    private val DayMonth: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.US)
    private val WeekdayDayMonth: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)

    fun days(n: Int): String = if (n == 1) "day" else "days"

    fun range(firstDay: LocalDate): String = "Last 15 weeks, ${firstDay.format(DayMonth)} to today"

    fun minutes(minutes: Int): String = when {
        minutes < MINUTES_PER_HOUR -> "${minutes}m"
        else -> "${minutes / MINUTES_PER_HOUR}h ${minutes % MINUTES_PER_HOUR}m"
    }

    private const val MINUTES_PER_HOUR = 60

    fun bestDay(day: ReadingCardDay): String = "Best day: ${day.date.format(WeekdayDayMonth)}, ${minutes(day.minutes)}"

    fun lastSeven(minutes: Int): String = "Last 7 days · ${minutes(minutes)}"

    fun average(minutes: Int): String = "$minutes min a day"

    fun weekday(date: LocalDate): String = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US)

    fun description(model: ReadingCardModel): String =
        "Reading: ${model.streak}-day streak, ${model.daysRead} days read in the last 15 weeks, " +
            "${minutes(model.weekMinutes)} in the last 7 days; opens the Journal"
}

private val WeekdayLabels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val HeatAlphas = listOf(0.3f, 0.5f, 0.75f, 1f)
private const val DaysInWeek = 7
private const val CellRounding = 0.28f
private const val STREAK_ARGB = 0xFFE8A33D

/** The streak's flame: the bookmark ribbon's amber. */
private val StreakTint = Color(STREAK_ARGB)
private val SmallIcon = 16.dp
private val TinyIcon = 13.dp
private val MaxCell: Dp = 18.dp
private val CellGap: Dp = 3.dp
private val DayLabelWidth: Dp = 26.dp
private val MonthRowHeight: Dp = 14.dp
internal val TileLineGap: Dp = 4.dp
private val TileExtraInset: Dp = 4.dp
private val TileUnitNudge: Dp = 2.dp
private val LegendSwatch: Dp = 11.dp
