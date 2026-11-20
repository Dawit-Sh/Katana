package com.example.unifiedreader.feature.reader.core

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.unifiedreader.feature.reader.preferences.ComicReadingDirection

/**
 * The dock's progress row after its leading button: the percentage, the slider and the position
 * ("p. 37 / 412", "2–3 / 8"). The slider is an 8 dp track filled in the accent to the reading
 * position, with 2 × 14 dp ticks at the chapters and a 4 × 20 dp knob. While the knob is
 * dragged the fill follows the finger; the reader only moves on release, so a long drag does not
 * load every page it passes.
 */
@Composable
fun RowScope.ReaderDockProgress(
    progress: Float,
    percentLabel: String,
    positionLabel: String,
    readingDirection: ComicReadingDirection = ComicReadingDirection.LTR,
    chapterMarks: List<Float> = emptyList(),
    onProgressSelected: ((Float) -> Unit)? = null,
) {
    val tokens = readerOverlayTokens()
    var dragProgress by remember { mutableStateOf<Float?>(null) }
    val clampedProgress = (dragProgress ?: progress).coerceIn(0f, 1f)
    val visualProgress = orientReaderProgress(
        progress = clampedProgress,
        readingDirection = readingDirection,
    )
    val fillRange = resolveReaderProgressFillRange(
        progress = clampedProgress,
        readingDirection = readingDirection,
    )
    val metaStyle = MaterialTheme.typography.labelMedium.copy(
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
    )
    Layout(
        modifier = Modifier.weight(1f),
        content = {
            Text(
                text = percentLabel,
                style = metaStyle,
                color = tokens.colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
            Canvas(
                modifier = Modifier
                    .height(ReaderDockDefaults.sliderTouchHeight)
                    .then(
                        if (onProgressSelected != null) {
                            Modifier
                                .pointerInput(onProgressSelected, readingDirection) {
                                    detectTapGestures { offset ->
                                        if (size.width <= 0) return@detectTapGestures
                                        onProgressSelected(
                                            resolveReaderProgressSelection(
                                                horizontalFraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f),
                                                readingDirection = readingDirection,
                                            ),
                                        )
                                    }
                                }
                                .pointerInput(onProgressSelected, readingDirection) {
                                    fun progressAt(x: Float): Float = resolveReaderProgressSelection(
                                        horizontalFraction = (x / size.width.toFloat()).coerceIn(0f, 1f),
                                        readingDirection = readingDirection,
                                    )
                                    detectHorizontalDragGestures(
                                        onDragStart = { offset ->
                                            if (size.width > 0) dragProgress = progressAt(offset.x)
                                        },
                                        onDragEnd = {
                                            dragProgress?.let(onProgressSelected)
                                            dragProgress = null
                                        },
                                        onDragCancel = { dragProgress = null },
                                        onHorizontalDrag = { change, _ ->
                                            if (size.width > 0) dragProgress = progressAt(change.position.x)
                                            change.consume()
                                        },
                                    )
                                }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                val centerY = size.height / 2f
                val trackHeight = ReaderDockDefaults.trackHeight.toPx()
                val trackTop = centerY - trackHeight / 2f
                val radius = CornerRadius(trackHeight / 2f)
                drawRoundRect(
                    color = tokens.colors.track,
                    topLeft = Offset(0f, trackTop),
                    size = Size(size.width, trackHeight),
                    cornerRadius = radius,
                )
                val fillStart = size.width * fillRange.startFraction
                val fillEnd = size.width * fillRange.endFraction
                if (fillEnd > fillStart) {
                    drawRoundRect(
                        color = tokens.colors.accent,
                        topLeft = Offset(fillStart, trackTop),
                        size = Size(fillEnd - fillStart, trackHeight),
                        cornerRadius = radius,
                    )
                }
                val tickWidth = ReaderDockDefaults.tickWidth.toPx()
                val tickHalfHeight = ReaderDockDefaults.tickHeight.toPx() / 2f
                chapterMarks
                    .asSequence()
                    .map { mark -> orientReaderProgress(progress = mark.coerceIn(0f, 1f), readingDirection = readingDirection) }
                    .filter { it > 0f && it < 1f }
                    .distinct()
                    .forEach { mark ->
                        val x = size.width * mark
                        // A tick reads against what is under it: the fill or the bare track.
                        val onFill = x in fillStart..fillEnd
                        drawRoundRect(
                            color = if (onFill) {
                                tokens.colors.container.copy(alpha = 0.55f)
                            } else {
                                tokens.colors.secondaryText.copy(alpha = 0.7f)
                            },
                            topLeft = Offset(x - tickWidth / 2f, centerY - tickHalfHeight),
                            size = Size(tickWidth, tickHalfHeight * 2f),
                            cornerRadius = CornerRadius(tickWidth / 2f),
                        )
                    }
                val knobWidth = ReaderDockDefaults.knobWidth.toPx()
                val knobHeight = ReaderDockDefaults.knobHeight.toPx()
                val knobX = readerDockKnobCenter(width = size.width, progress = visualProgress, knobWidth = knobWidth)
                drawRoundRect(
                    color = tokens.colors.primaryText,
                    topLeft = Offset(knobX - knobWidth / 2f, centerY - knobHeight / 2f),
                    size = Size(knobWidth, knobHeight),
                    cornerRadius = CornerRadius(knobWidth / 2f),
                )
            }
            Text(
                text = positionLabel,
                style = metaStyle,
                color = tokens.colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        },
        measurePolicy = ReaderDockProgressMeasurePolicy,
    )
}

/**
 * The percentage, the slider and the position in one row, as at normal sizes; or, when the labels would leave the
 * slider less than [ReaderDockDefaults.minSliderWidth] (very large text), the labels on a row of their own above a
 * slider as wide as the dock.
 */
private val ReaderDockProgressMeasurePolicy = MeasurePolicy { measurables, constraints ->
    val (percent, slider, position) = measurables
    val width = constraints.maxWidth
    val gap = ReaderDockDefaults.labelGap.roundToPx()
    val sliderHeight = ReaderDockDefaults.sliderTouchHeight.roundToPx()
    val percentLabel = percent.measure(Constraints(maxWidth = width))
    val positionWidth = position.maxIntrinsicWidth(Constraints.Infinity)
    val besidePercent = (width - percentLabel.width - gap).coerceAtLeast(0)
    val stacked = readerDockLabelsAboveSlider(
        width = width,
        percentWidth = percentLabel.width,
        positionWidth = positionWidth,
        gap = gap,
        minSliderWidth = ReaderDockDefaults.minSliderWidth.roundToPx(),
    )
    if (!stacked) {
        val positionLabel = position.measure(Constraints(maxWidth = besidePercent))
        val sliderWidth = (width - percentLabel.width - positionLabel.width - 2 * gap).coerceAtLeast(0)
        val track = slider.measure(Constraints.fixed(sliderWidth, sliderHeight))
        val height = maxOf(percentLabel.height, sliderHeight, positionLabel.height)
        layout(width, height) {
            percentLabel.placeRelative(0, (height - percentLabel.height) / 2)
            track.placeRelative(percentLabel.width + gap, (height - sliderHeight) / 2)
            positionLabel.placeRelative(width - positionLabel.width, (height - positionLabel.height) / 2)
        }
    } else {
        // Over the slider the two labels share a row, or, when even that is too narrow, the position gets its own.
        val sharedRow = positionWidth <= besidePercent
        val positionLabel = position.measure(Constraints(maxWidth = if (sharedRow) besidePercent else width))
        val track = slider.measure(Constraints.fixed(width, sliderHeight))
        val labelsHeight = if (sharedRow) {
            maxOf(percentLabel.height, positionLabel.height)
        } else {
            percentLabel.height + positionLabel.height
        }
        layout(width, labelsHeight + sliderHeight) {
            if (sharedRow) {
                percentLabel.placeRelative(0, labelsHeight - percentLabel.height)
                positionLabel.placeRelative(width - positionLabel.width, labelsHeight - positionLabel.height)
            } else {
                percentLabel.placeRelative(0, 0)
                positionLabel.placeRelative(width - positionLabel.width, percentLabel.height)
            }
            track.placeRelative(0, labelsHeight)
        }
    }
}

/** Whether the labels go above the slider: side by side they would leave it less than [minSliderWidth]. */
internal fun readerDockLabelsAboveSlider(
    width: Int,
    percentWidth: Int,
    positionWidth: Int,
    gap: Int,
    minSliderWidth: Int,
): Boolean = width - percentWidth - positionWidth - 2 * gap < minSliderWidth

/**
 * Where the knob's centre sits on a [width]-wide track at [progress]: kept inside the track, or in its middle when
 * the track is no wider than the knob (very large text and display sizes can squeeze it to nothing).
 */
internal fun readerDockKnobCenter(width: Float, progress: Float, knobWidth: Float): Float {
    val half = knobWidth / 2f
    return if (width <= knobWidth) width / 2f else (width * progress).coerceIn(half, width - half)
}

/** Sizes of the dock's row and slider (the 8.7 spec). */
object ReaderDockDefaults {
    val rowHeight: Dp = 60.dp
    val trackHeight: Dp = 8.dp
    val tickWidth: Dp = 2.dp
    val tickHeight: Dp = 14.dp
    val knobWidth: Dp = 4.dp
    val knobHeight: Dp = 20.dp

    /** The slider's touch target; the track is drawn centred in it. */
    val sliderTouchHeight: Dp = 32.dp

    /** Between the percentage, the slider and the position (the dock row's own spacing). */
    val labelGap: Dp = 12.dp

    /**
     * Narrower than this beside its labels, the slider gets a row of its own under them. At normal text a 384 dp
     * phone leaves it about 114 dp ("p. 10 / 357"), a 360 dp one about 90; 200% text leaves it nothing.
     */
    val minSliderWidth: Dp = 64.dp
}

internal fun orientReaderProgress(
    progress: Float,
    readingDirection: ComicReadingDirection,
): Float = when (readingDirection) {
    ComicReadingDirection.LTR -> progress.coerceIn(0f, 1f)
    ComicReadingDirection.RTL -> 1f - progress.coerceIn(0f, 1f)
}

internal data class ReaderProgressFillRange(
    val startFraction: Float,
    val endFraction: Float,
)

internal fun resolveReaderProgressFillRange(
    progress: Float,
    readingDirection: ComicReadingDirection,
): ReaderProgressFillRange {
    val visualProgress = orientReaderProgress(
        progress = progress,
        readingDirection = readingDirection,
    )
    return when (readingDirection) {
        ComicReadingDirection.LTR -> ReaderProgressFillRange(
            startFraction = 0f,
            endFraction = visualProgress,
        )
        ComicReadingDirection.RTL -> ReaderProgressFillRange(
            startFraction = visualProgress,
            endFraction = 1f,
        )
    }
}

internal fun resolveReaderProgressSelection(
    horizontalFraction: Float,
    readingDirection: ComicReadingDirection,
): Float = when (readingDirection) {
    ComicReadingDirection.LTR -> horizontalFraction.coerceIn(0f, 1f)
    ComicReadingDirection.RTL -> 1f - horizontalFraction.coerceIn(0f, 1f)
}
