package com.example.unifiedreader.feature.journal

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.roundToInt

/** How the heat map's squares fit a space: [weeks] columns of [cell]-sized squares. */
data class HeatMapFit(val weeks: Int, val cell: Float)

/**
 * The squares for a heat map of [weeks] columns across [width], with [height] of room for the squares
 * (under the month names), [gap] between them: as big as both allow within [cells]. Only where even the
 * smallest squares can't put every week across [width] do the oldest weeks go (a column is a week, so
 * fewer weeks would not make the map any shorter).
 */
fun heatMapFit(
    width: Float,
    height: Float,
    weeks: Int,
    gap: Float,
    cells: ClosedFloatingPointRange<Float>,
): HeatMapFit {
    val shown = weeks.coerceAtMost(floor((width + gap) / (cells.start + gap)).toInt()).coerceAtLeast(1)
    val acrossWidth = (width - gap * (shown - 1)) / shown
    val downHeight = (height - gap * (DAYS - 1)) / DAYS
    val cell = minOf(acrossWidth, downHeight, cells.endInclusive).coerceAtLeast(cells.start)
    return HeatMapFit(weeks = shown, cell = cell)
}

/** The squares' height: seven of [cell] and the six [gap]s between. */
fun heatMapGridHeight(cell: Float, gap: Float): Float = cell * DAYS + gap * (DAYS - 1)

/**
 * The side menu's heat map: [columns] (weeks, Monday on top, the newest last) as squares in [shades]
 * by their goal step, month names over the columns their first days fall in, today outlined, the
 * days still to come left out. It fills its width, with squares as big as its height allows (up to
 * [maxCell]); its intrinsic heights are those of the biggest and smallest squares, so the menu can
 * work out what fits before it measures.
 */
@Composable
fun ReadingHeatMap(
    columns: List<List<ReadingCardDay>>,
    shades: List<Color>,
    modifier: Modifier = Modifier,
    maxCell: Dp = HeatMapDefaults.MaxCell,
) {
    val labels = remember(columns) { readingCardMonthLabels(columns) }
    val todayRing = MaterialTheme.colorScheme.onSurface
    val policy = remember(columns.size, labels, maxCell) { HeatMapPolicy(columns.size, labels.map { it.first }, maxCell) }
    Layout(
        modifier = modifier,
        content = {
            labels.forEach { (_, name) ->
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Canvas(modifier = Modifier) {
                val gap = HeatMapDefaults.Gap.toPx()
                val cell = (size.height - gap * (DAYS - 1)) / DAYS
                val step = cell + gap
                val shown = ((size.width + gap) / step).roundToInt().coerceIn(0, columns.size)
                val radius = CornerRadius(cell * CellRounding)
                val ring = HeatMapDefaults.TodayRing.toPx()
                val ringOffset = HeatMapDefaults.TodayRingOffset.toPx() + ring / 2
                columns.takeLast(shown).forEachIndexed { x, week ->
                    week.forEachIndexed { y, day ->
                        if (day.isFuture) return@forEachIndexed
                        val topLeft = Offset(x * step, y * step)
                        drawRoundRect(shades[day.step.coerceIn(0, shades.lastIndex)], topLeft, Size(cell, cell), radius)
                        if (day.isToday) {
                            drawRoundRect(
                                color = todayRing,
                                topLeft = Offset(topLeft.x - ringOffset, topLeft.y - ringOffset),
                                size = Size(cell + ringOffset * 2, cell + ringOffset * 2),
                                cornerRadius = CornerRadius(cell * CellRounding + ringOffset),
                                style = Stroke(ring),
                            )
                        }
                    }
                }
            }
        },
        measurePolicy = policy,
    )
}

/** The heat map's sizes. */
object HeatMapDefaults {
    val Gap: Dp = 3.dp
    val MinCell: Dp = 8.dp
    val MaxCell: Dp = 18.dp
    val MonthGap: Dp = 4.dp
    val TodayRing: Dp = 1.5.dp
    val TodayRingOffset: Dp = 1.dp
}

/**
 * Measures the month names (every child but the last) over the squares (the last, a canvas): the
 * squares take the room left under the names, see [heatMapFit]; the grid is centred in the width.
 */
private class HeatMapPolicy(
    private val weeks: Int,
    private val labelColumns: List<Int>,
    private val maxCell: Dp,
) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val labels = measurables.dropLast(1).map { it.measure(Constraints()) }
        val labelHeight = labels.maxOfOrNull { it.height } ?: 0
        val top = if (labels.isEmpty()) 0 else labelHeight + HeatMapDefaults.MonthGap.roundToPx()
        val width = constraints.maxWidth
        val room = if (constraints.hasBoundedHeight) (constraints.maxHeight - top).toFloat() else Float.MAX_VALUE
        val gap = HeatMapDefaults.Gap.toPx()
        val fit = heatMapFit(width.toFloat(), room, weeks, gap, HeatMapDefaults.MinCell.toPx()..maxCell.toPx())
        val gridWidth = (fit.cell * fit.weeks + gap * (fit.weeks - 1)).roundToInt()
        val gridHeight = heatMapGridHeight(fit.cell, gap).roundToInt()
        val grid = measurables.last().measure(Constraints.fixed(gridWidth, gridHeight))
        val left = ((width - gridWidth) / 2).coerceAtLeast(0)
        val dropped = weeks - fit.weeks
        val step = fit.cell + gap
        return layout(width, top + gridHeight) {
            labels.forEachIndexed { index, label ->
                val column = labelColumns[index] - dropped
                // A name over a week no longer shown, or one that would run past the edge, is left out.
                val x = left + (column * step).roundToInt()
                if (column >= 0 && x + label.width <= width) label.place(x, 0)
            }
            grid.place(left, top)
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        heightFor(measurables, width, HeatMapDefaults.MinCell.toPx())

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        heightFor(measurables, width, Float.MAX_VALUE)

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        (HeatMapDefaults.MinCell.toPx() * weeks + HeatMapDefaults.Gap.toPx() * (weeks - 1)).roundToInt()

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        (maxCell.toPx() * weeks + HeatMapDefaults.Gap.toPx() * (weeks - 1)).roundToInt()

    /** The whole map's height at [width] with squares no bigger than [cellCap]. */
    private fun IntrinsicMeasureScope.heightFor(measurables: List<IntrinsicMeasurable>, width: Int, cellCap: Float): Int {
        val labelHeight = measurables.dropLast(1).maxOfOrNull { it.maxIntrinsicHeight(Constraints.Infinity) } ?: 0
        val top = if (labelHeight == 0) 0 else labelHeight + HeatMapDefaults.MonthGap.roundToPx()
        val gap = HeatMapDefaults.Gap.toPx()
        val roomForCap = heatMapGridHeight(cellCap.coerceAtMost(maxCell.toPx()), gap)
        val fit = heatMapFit(width.toFloat(), roomForCap, weeks, gap, HeatMapDefaults.MinCell.toPx()..maxCell.toPx())
        return top + heatMapGridHeight(fit.cell, gap).roundToInt()
    }
}

private const val DAYS = 7
private const val CellRounding = 0.25f
