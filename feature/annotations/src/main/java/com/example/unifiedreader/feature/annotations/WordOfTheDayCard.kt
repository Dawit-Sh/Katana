package com.example.unifiedreader.feature.annotations

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.unifiedreader.ui.components.AppBookType
import com.example.unifiedreader.ui.components.AppButton
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppPanel
import com.example.unifiedreader.ui.components.AppPanelDefaults
import com.example.unifiedreader.ui.components.AppTonalButton
import com.example.unifiedreader.ui.components.AppTonalIconButton
import com.example.unifiedreader.ui.theme.UnifiedReaderThemeTokens

/**
 * The side menu's word of the day: "Word of the day" with the word's progress dot and its book, the word
 * in the book face, its [meaning] on one line, then Review (the review on this word), I know it (a good
 * review; "Known" once answered today) and, with more than one word, → for another. Given less height
 * than it needs for the meaning, it leaves the meaning out; its intrinsic heights are with and without
 * it, so the menu can tell what fits.
 */
@Composable
fun WordOfTheDayCard(
    word: SavedWord,
    meaning: String?,
    knownToday: Boolean,
    onReview: () -> Unit,
    onKnow: () -> Unit,
    onNext: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    AppPanel(modifier = modifier, color = AppPanelDefaults.containerColor) {
        Layout(
            modifier = Modifier.padding(
                start = WordOfTheDayDefaults.StartInset,
                top = WordOfTheDayDefaults.Inset,
                end = WordOfTheDayDefaults.Inset,
                bottom = WordOfTheDayDefaults.Inset,
            ),
            content = {
                LabelRow(word)
                Text(
                    text = word.word,
                    style = AppBookType.title(MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp)),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = meaning.orEmpty(),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Actions(knownToday = knownToday, onReview = onReview, onKnow = onKnow, onNext = onNext)
            },
            measurePolicy = WordCardPolicy(hasMeaning = !meaning.isNullOrBlank()),
        )
    }
}

/** "Word of the day", and at the end the word's progress dot and the book it came from. */
@Composable
private fun LabelRow(word: SavedWord) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val progress = word.progress()
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = WordOfTheDayCopy.description(word, progress)
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = WordOfTheDayCopy.Label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
            fontWeight = FontWeight.SemiBold,
            color = muted,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.width(WordOfTheDayDefaults.LabelGap))
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(WordOfTheDayDefaults.DotGap, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProgressDot(progress = progress)
            Text(
                text = word.book.title,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
                fontWeight = FontWeight.Medium,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/** A ring for a new word, half filled while it is being learned, full once learned. */
@Composable
private fun ProgressDot(progress: WordProgress) {
    val ring = when (progress) {
        WordProgress.New -> MaterialTheme.colorScheme.onSurfaceVariant
        WordProgress.Learning -> MaterialTheme.colorScheme.primary
        WordProgress.Learned -> UnifiedReaderThemeTokens.colors.readingDay
    }
    Canvas(modifier = Modifier.size(WordOfTheDayDefaults.Dot)) {
        val stroke = WordOfTheDayDefaults.DotStroke.toPx()
        val radius = (size.minDimension - stroke) / 2
        when (progress) {
            WordProgress.New -> Unit
            WordProgress.Learning ->
                drawArc(ring, startAngle = HalfStart, sweepAngle = HalfSweep, useCenter = true, size = Size(size.width, size.height))
            WordProgress.Learned -> drawCircle(ring)
        }
        drawCircle(ring, radius = radius, center = Offset(size.width / 2, size.height / 2), style = Stroke(stroke))
    }
}

/** Review, I know it, and → at the end. */
@Composable
private fun Actions(
    knownToday: Boolean,
    onReview: () -> Unit,
    onKnow: () -> Unit,
    onNext: (() -> Unit)?,
) {
    val pill = PaddingValues(horizontal = WordOfTheDayDefaults.PillInset)
    val label = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(WordOfTheDayDefaults.PillGap), verticalAlignment = Alignment.CenterVertically) {
        AppButton(onClick = onReview, modifier = Modifier.height(WordOfTheDayDefaults.Pill), contentPadding = pill) {
            Icon(imageVector = AppIcons.Style, contentDescription = null, modifier = Modifier.size(WordOfTheDayDefaults.PillIcon))
            Spacer(modifier = Modifier.width(WordOfTheDayDefaults.PillIconGap))
            Text(text = WordOfTheDayCopy.Review, style = label, maxLines = 1)
        }
        AppTonalButton(
            onClick = onKnow,
            enabled = !knownToday,
            modifier = Modifier.height(WordOfTheDayDefaults.Pill),
            contentPadding = pill,
        ) {
            if (knownToday) {
                Icon(imageVector = AppIcons.Check, contentDescription = null, modifier = Modifier.size(WordOfTheDayDefaults.PillIcon))
                Spacer(modifier = Modifier.width(WordOfTheDayDefaults.PillIconGap))
                Text(text = WordOfTheDayCopy.Known, style = label, maxLines = 1)
            } else {
                Text(text = WordOfTheDayCopy.IKnowIt, style = label, maxLines = 1)
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        if (onNext != null) {
            AppTonalIconButton(onClick = onNext, modifier = Modifier.size(WordOfTheDayDefaults.Pill), shape = CircleShape) {
                Icon(
                    imageVector = AppIcons.ArrowForward,
                    contentDescription = WordOfTheDayCopy.Another,
                    modifier = Modifier.size(WordOfTheDayDefaults.PillIcon),
                )
            }
        }
    }
}

/**
 * The label, the word, the meaning, the actions, top to bottom. The meaning (the third child) shows
 * only where the height allows it and there is one.
 */
private class WordCardPolicy(private val hasMeaning: Boolean) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val loose = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
        val placeables = measurables.map { it.measure(loose) }
        val label = placeables[LABEL]
        val word = placeables[WORD]
        val meaning = placeables[MEANING]
        val actions = placeables[ACTIONS]
        val wordGap = WordOfTheDayDefaults.WordGap.roundToPx()
        val meaningGap = WordOfTheDayDefaults.MeaningGap.roundToPx()
        val actionsGap = WordOfTheDayDefaults.ActionsGap.roundToPx()
        val short = label.height + wordGap + word.height + actionsGap + actions.height
        val full = short + meaningGap + meaning.height
        val showMeaning = hasMeaning && (!constraints.hasBoundedHeight || full <= constraints.maxHeight)
        val width = constraints.maxWidth.takeIf { constraints.hasBoundedWidth } ?: listOf(label, word, meaning, actions).maxOf { it.width }
        val height = (if (showMeaning) full else short).coerceIn(constraints.minHeight, constraints.maxHeight)
        return layout(width, height) {
            var y = 0
            label.place(0, y)
            y += label.height + wordGap
            word.place(0, y)
            y += word.height
            if (showMeaning) {
                meaning.place(0, y + meaningGap)
                y += meaningGap + meaning.height
            }
            actions.place(0, y + actionsGap)
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        heightFor(measurables, width, withMeaning = false)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        heightFor(measurables, width, withMeaning = hasMeaning)

    private fun IntrinsicMeasureScope.heightFor(measurables: List<IntrinsicMeasurable>, width: Int, withMeaning: Boolean): Int {
        val heights = measurables.map { it.maxIntrinsicHeight(width) }
        val short = heights[LABEL] + WordOfTheDayDefaults.WordGap.roundToPx() + heights[WORD] +
            WordOfTheDayDefaults.ActionsGap.roundToPx() + heights[ACTIONS]
        return if (withMeaning) short + WordOfTheDayDefaults.MeaningGap.roundToPx() + heights[MEANING] else short
    }
}

/** What the word card says. */
object WordOfTheDayCopy {
    const val Label = "Word of the day"
    const val Review = "Review"
    const val IKnowIt = "I know it"
    const val Known = "Known"
    const val Another = "Another word"

    fun description(word: SavedWord, progress: WordProgress): String {
        val state = when (progress) {
            WordProgress.New -> "new, not reviewed yet"
            WordProgress.Learning -> "learning"
            WordProgress.Learned -> "learned"
        }
        return "$Label, $state, from ${word.book.title}"
    }
}

/** The word card's sizes. */
object WordOfTheDayDefaults {
    val Inset: Dp = 12.dp
    val StartInset: Dp = 14.dp
    val WordGap: Dp = 4.dp
    val MeaningGap: Dp = 2.dp
    val ActionsGap: Dp = 10.dp
    val LabelGap: Dp = 8.dp
    val Dot: Dp = 10.dp
    val DotStroke: Dp = 1.5.dp
    val DotGap: Dp = 6.dp
    val Pill: Dp = 32.dp
    val PillInset: Dp = 12.dp
    val PillGap: Dp = 6.dp
    val PillIcon: Dp = 16.dp
    val PillIconGap: Dp = 6.dp
}

// The card's children, in order.
private const val LABEL = 0
private const val WORD = 1
private const val MEANING = 2
private const val ACTIONS = 3

// The left half of the dot: from 90 degrees (the bottom), half way round.
private const val HalfStart = 90f
private const val HalfSweep = 180f
