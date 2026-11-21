package com.example.unifiedreader.feature.journal

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.example.unifiedreader.journal.ReadingGoal
import com.example.unifiedreader.ui.components.AppPanelDefaults
import com.example.unifiedreader.ui.theme.UnifiedReaderThemeTokens
import kotlin.math.max
import kotlin.math.min

/**
 * The reading days' five shades on [ground] (the panel they sit on), one per [ReadingGoal] step: a faint
 * square for no reading, then the theme's reading-day colour ever stronger up to its full strength for
 * twice the goal. The side menu's heat map and the Journal's calendars both use them.
 */
@Composable
fun readingDayShades(ground: Color = AppPanelDefaults.containerColor): List<Color> {
    val full = UnifiedReaderThemeTokens.colors.readingDay
    val faint = MaterialTheme.colorScheme.outlineVariant
    return remember(ground, full, faint) {
        listOf(faint.copy(alpha = NoReadingMix).compositeOver(ground)) +
            StepMixes.map { share -> lerp(ground, full, share) } +
            full
    }
}

/** Text on a reading day's [fill]: whichever of the theme's surface and on-surface reads better on it. */
@Composable
internal fun textOnReadingDay(fill: Color): Color {
    val light = MaterialTheme.colorScheme.surface
    val dark = MaterialTheme.colorScheme.onSurface
    return if (contrast(fill, light) >= contrast(fill, dark)) light else dark
}

private fun contrast(a: Color, b: Color): Float {
    val la = a.luminance() + ContrastOffset
    val lb = b.luminance() + ContrastOffset
    return max(la, lb) / min(la, lb)
}

/** How much of the outline a day without reading shows, and how much of the full colour each step between. */
private const val NoReadingMix = 0.34f
private val StepMixes = listOf(0.28f, 0.52f, 0.78f)
private const val ContrastOffset = 0.05f
