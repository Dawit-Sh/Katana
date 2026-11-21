package com.example.unifiedreader.feature.journal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.unifiedreader.journal.ReadingGoal
import com.example.unifiedreader.ui.components.AppLayout
import com.example.unifiedreader.ui.components.AppWindowWidth
import com.example.unifiedreader.ui.components.currentAppWindowWidth
import java.util.Calendar
import java.util.Locale
import kotlin.math.min

/**
 * Reading days in Month: the month as small squares, each shaded by its minutes of reading against
 * the daily goal ([ReadingGoal]: under half, under the goal, the goal met, twice the goal), today
 * ringed, a dot on a day something was finished; a day without reading is a faint square and a day
 * still to come only an outline.
 * From Medium the squares sit beside the heading, the best day and the key, so the month takes
 * the height of its squares; on phones they fill the width under the heading.
 */
@Composable
internal fun JournalMonthReadingDaysPanel(
    overview: JournalPeriodOverview,
    nowEpochMillis: Long,
    onDaySelected: (JournalActivityDay) -> Unit,
    modifier: Modifier = Modifier,
) {
    val daysByKey = remember(overview) { overview.activityDays.associateBy(JournalActivityDay::localDayKey) }
    val best = remember(overview) { journalBestDay(overview.activityDays) }
    val showFinished = overview.activityDays.any { day -> day.finishedCount > 0 }
    val compact = currentAppWindowWidth() == AppWindowWidth.Compact
    val squares = @Composable { gridModifier: Modifier ->
        MonthGrid(
            monthStartEpochMillis = overview.periodStartEpochMillis,
            daysByKey = daysByKey,
            todayKey = localDateKey(nowEpochMillis),
            cellMaxHeight = MonthSquareMaxSize,
            gap = MonthSquareGap,
            corner = MonthSquareCorner,
            showNumbers = true,
            showFinished = true,
            onDaySelected = onDaySelected,
            modifier = gridModifier,
        )
    }
    JournalPanel(modifier = modifier) {
        if (compact) {
            JournalSectionHeader(title = "Reading days", summary = "Tap a day for what you read")
            squares(Modifier.padding(top = AppLayout.headingGap * 2).widthIn(max = MonthSquareMaxSize * 7 + MonthSquareGap * 6))
            best?.let { day -> BestDayLine(day, Modifier.padding(top = AppLayout.headingGap * 2)) }
            HeatLegend(showFinished = showFinished, modifier = Modifier.padding(top = AppLayout.headingGap))
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(MonthSideGap),
            ) {
                squares(Modifier.width(MonthSquareSize * 7 + MonthSquareGap * 6))
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    JournalSectionHeader(title = "Reading days", summary = "Tap a day for what you read")
                    best?.let { day -> BestDayLine(day, Modifier.padding(top = AppLayout.headingGap)) }
                    Spacer(modifier = Modifier.weight(1f).heightIn(min = AppLayout.headingGap))
                    HeatLegend(showFinished = showFinished)
                }
            }
        }
    }
}

/** The month's longest day of reading, or null when nothing was read. */
internal fun journalBestDay(days: List<JournalActivityDay>): JournalActivityDay? =
    days.filter { day -> day.readingMillis > 0L }.maxByOrNull(JournalActivityDay::readingMillis)

/** "18 Nov, 8 min". */
internal fun JournalActivityDay.bestDayLabel(): String = "$dayOfMonth ${shortMonthName(month)}, ${formatJournalMinutes(readingMillis)}"

/** "Best day: 18 Nov, 8 min", the day and its time in the text's own colour. */
@Composable
private fun BestDayLine(
    day: JournalActivityDay,
    modifier: Modifier = Modifier,
) {
    Text(
        text = buildAnnotatedString {
            append("Best day: ")
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)) {
                append(day.bestDayLabel())
            }
        },
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Reading days in Year: the twelve months, [columns] across, as squares without day numbers. */
@Composable
internal fun JournalYearReadingDaysPanel(
    overview: JournalPeriodOverview,
    nowEpochMillis: Long,
    onDaySelected: (JournalActivityDay) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = journalYearMonthColumns(compact = currentAppWindowWidth() == AppWindowWidth.Compact),
) {
    val daysByKey = remember(overview) { overview.activityDays.associateBy(JournalActivityDay::localDayKey) }
    JournalPanel(modifier = modifier) {
        JournalSectionHeader(title = "Reading days", summary = "Tap a day for what you read")
        Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            (Calendar.JANUARY..Calendar.DECEMBER).chunked(columns).forEach { months ->
                JournalEqualRow(count = columns, gap = YearMonthGap) {
                    months.forEach { month ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = longMonthName(month),
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            MonthGrid(
                                monthStartEpochMillis = monthStartInYear(overview.periodStartEpochMillis, month),
                                daysByKey = daysByKey,
                                todayKey = localDateKey(nowEpochMillis),
                                cellMaxHeight = YearCellMaxHeight,
                                gap = YearCellGap,
                                corner = YearCellCorner,
                                showNumbers = false,
                                showFinished = false,
                                onDaySelected = onDaySelected,
                            )
                        }
                    }
                    repeat(columns - months.size) { Spacer(modifier = Modifier) }
                }
            }
        }
        HeatLegend(showFinished = false, modifier = Modifier.padding(top = 18.dp))
    }
}

/** Months across in Year: four on a tablet (the panel spans eight twelfths, or a portrait window), three on phones. */
internal fun journalYearMonthColumns(compact: Boolean): Int = if (compact) 3 else 4

/**
 * A month's days in weeks of seven, each day a square, an equal share of the width but never
 * taller than [cellMaxHeight], laid out by [JournalEqualRow] so a row of panels can ask for its height.
 */
@Composable
private fun MonthGrid(
    monthStartEpochMillis: Long,
    daysByKey: Map<Int, JournalActivityDay>,
    todayKey: Int,
    cellMaxHeight: Dp,
    gap: Dp,
    corner: Dp,
    showNumbers: Boolean,
    showFinished: Boolean,
    onDaySelected: (JournalActivityDay) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cells = remember(monthStartEpochMillis) { monthCells(monthStartEpochMillis) }
    val cellSize = Modifier.cellSize(cellMaxHeight)
    val goal = LocalJournalReadingGoal.current.minutes
    val shades = readingDayShades()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        if (showNumbers) {
            JournalEqualRow(count = 7, gap = gap) {
                listOf("M", "T", "W", "T", "F", "S", "S").forEach { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        cells.chunked(7).forEach { week ->
            JournalEqualRow(count = 7, gap = gap) {
                week.forEach { day ->
                    if (day == null) {
                        Spacer(modifier = cellSize)
                    } else {
                        val activity = daysByKey[day.localDayKey]?.takeIf { it.heatValue > 0 }
                        HeatCell(
                            dayOfMonth = day.dayOfMonth.takeIf { showNumbers },
                            level = activity?.let { journalReadingDayStep(it, goal) } ?: 0,
                            shades = shades,
                            future = day.localDayKey > todayKey,
                            finished = showFinished && (activity?.finishedCount ?: 0) > 0,
                            today = day.localDayKey == todayKey,
                            corner = corner,
                            onClick = activity?.let { { onDaySelected(it) } },
                            description = activity?.cellDescription(),
                            modifier = cellSize,
                        )
                    }
                }
                // The last week keeps its days the width of the others.
                repeat(7 - week.size) { Spacer(modifier = cellSize) }
            }
        }
    }
}

/**
 * A day's square: the width its row gives it and as tall, but never taller than [maxHeight], so
 * a calendar across a wide panel stays a calendar's height.
 */
private fun Modifier.cellSize(maxHeight: Dp): Modifier = layout { measurable, constraints ->
    val width = constraints.maxWidth
    val height = min(width, maxHeight.roundToPx())
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(width, height) { placeable.place(0, 0) }
}

@Composable
private fun HeatCell(
    dayOfMonth: Int?,
    level: Int,
    shades: List<Color>,
    future: Boolean,
    finished: Boolean,
    today: Boolean,
    corner: Dp,
    onClick: (() -> Unit)?,
    description: String?,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val ring = scheme.onSurface
    val dot = scheme.tertiary
    val shape = RoundedCornerShape(corner)
    val fill = when {
        level > 0 -> Modifier.background(shades[level])
        future -> Modifier.border(1.dp, scheme.onSurface.copy(alpha = FutureOutlineAlpha), shape)
        else -> Modifier.background(shades[0])
    }
    Box(
        modifier = modifier
            .drawWithContent {
                drawContent()
                if (today) {
                    // Outside the square, clear of it by 1 dp.
                    val inset = -TodayRingOffset.toPx()
                    drawRoundRect(
                        color = ring,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - inset * 2, size.height - inset * 2),
                        cornerRadius = CornerRadius((corner + TodayRingOffset).toPx()),
                        style = Stroke(width = TodayRingWidth.toPx()),
                    )
                }
                if (finished) {
                    val radius = FinishedDotRadius.toPx()
                    val edge = CellTextInset.toPx()
                    drawCircle(color = dot, radius = radius, center = Offset(size.width - edge - radius, edge + radius))
                }
            }
            .clip(shape)
            .then(fill)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
    ) {
        if (dayOfMonth != null) {
            // The number in the top corner, as on a wall calendar.
            Box(modifier = Modifier.fillMaxSize().padding(start = CellTextInset, top = CellTextTop)) {
                CellText(
                    text = dayOfMonth.toString(),
                    color = cellTextColor(level, shades, future),
                    size = 11f,
                    weight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun cellTextColor(level: Int, shades: List<Color>, future: Boolean): Color {
    val scheme = MaterialTheme.colorScheme
    return when {
        level >= 3 -> textOnReadingDay(shades[level])
        level > 0 -> scheme.onSurface
        future -> scheme.onSurfaceVariant.copy(alpha = 0.5f)
        else -> scheme.onSurfaceVariant
    }
}

@Composable
private fun CellText(
    text: String,
    color: Color,
    size: Float,
    weight: FontWeight,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontSize = size.sp, lineHeight = (size + 2.5f).sp, fontFeatureSettings = "tnum"),
        fontWeight = weight,
        color = color,
        maxLines = 1,
        softWrap = false,
    )
}

/**
 * The key, "Less", the five shades, "More", each named for the goal; under it the finished dot where the
 * month has one, then the goal itself ("Goal 30 min a day"), which opens the goals to pick from.
 */
@Composable
private fun HeatLegend(
    showFinished: Boolean,
    modifier: Modifier = Modifier,
) {
    val textStyle = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp)
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val swatch = RoundedCornerShape(LegendSwatchCorner)
    val goal = LocalJournalReadingGoal.current
    val shades = readingDayShades()
    val names = ReadingGoal.stepNames(goal.minutes)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(LegendSwatchGap)) {
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) {
                contentDescription = "Reading days, from less to more: " + names.joinToString(", ")
            },
            horizontalArrangement = Arrangement.spacedBy(LegendSwatchGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Less", style = textStyle, color = color)
            shades.forEach { shade ->
                Box(modifier = Modifier.size(LegendSwatchSize).clip(swatch).background(shade))
            }
            Text(text = "More", style = textStyle, color = color)
        }
        if (showFinished) {
            Row(horizontalArrangement = Arrangement.spacedBy(LegendSwatchGap), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(FinishedDotRadius * 2).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary))
                Text(text = "Finished something", style = textStyle, color = color)
            }
        }
        JournalGoalPicker(goal = goal, textStyle = textStyle, color = color)
    }
}

private fun JournalActivityDay.cellDescription(): String = listOfNotNull(
    longLabel(),
    readingMillis.takeIf { it > 0L }?.let(::formatJournalMinutes),
    finishedCount.takeIf { it > 0 }?.let { "$it finished" },
).joinToString(", ")

private data class CalendarCell(
    val localDayKey: Int,
    val dayOfMonth: Int,
)

private fun monthCells(
    monthStartEpochMillis: Long,
): List<CalendarCell?> {
    val calendar = Calendar.getInstance(Locale.US).apply {
        timeInMillis = monthStartEpochMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        set(Calendar.DAY_OF_MONTH, 1)
    }
    val leadingBlanks = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7
    val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
    val cells = MutableList<CalendarCell?>(leadingBlanks) { null }
    repeat(daysInMonth) { index ->
        calendar.set(Calendar.DAY_OF_MONTH, index + 1)
        cells += CalendarCell(
            localDayKey = localDateKey(calendar.timeInMillis),
            dayOfMonth = index + 1,
        )
    }
    while (cells.size % 7 != 0) {
        cells += null
    }
    return cells
}

private fun monthStartInYear(
    yearStartEpochMillis: Long,
    month: Int,
): Long = Calendar.getInstance(Locale.US).apply {
    timeInMillis = yearStartEpochMillis
    set(Calendar.MONTH, month)
    set(Calendar.DAY_OF_MONTH, 1)
}.timeInMillis

// A day in Month: a 34 square beside the heading from Medium (the month 274 wide); on phones a
// seventh of the panel's width, no bigger than 44.
private val MonthSquareSize = 34.dp
private val MonthSquareMaxSize = 44.dp
private val MonthSquareGap = 6.dp
private val MonthSquareCorner = 8.dp
private val MonthSideGap = 18.dp
private val CellTextInset = 5.dp
private val CellTextTop = 3.dp
private val TodayRingOffset = 3.dp
private val TodayRingWidth = 2.dp
private val FinishedDotRadius = 3.5.dp
private const val FutureOutlineAlpha = 0.08f
private val LegendSwatchSize = 11.dp
private val LegendSwatchCorner = 3.dp
private val LegendSwatchGap = 5.dp
private val YearCellMaxHeight = 22.dp
private val YearCellGap = 3.dp
private val YearCellCorner = 4.dp
private val YearMonthGap = 22.dp
