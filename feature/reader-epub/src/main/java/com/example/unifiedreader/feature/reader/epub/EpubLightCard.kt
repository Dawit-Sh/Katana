package com.example.unifiedreader.feature.reader.epub

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.feature.reader.core.ReaderChromeDefaults
import com.example.unifiedreader.feature.reader.core.ReaderPanel
import com.example.unifiedreader.feature.reader.core.ReaderPanelSlot
import com.example.unifiedreader.feature.reader.core.readerOverlayTokens
import com.example.unifiedreader.feature.reader.preferences.ReaderComfort
import com.example.unifiedreader.ui.components.AppFloatingPanelPlacement
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppLayout
import com.example.unifiedreader.ui.components.AppPanelHeader
import com.example.unifiedreader.ui.components.AppSegment
import com.example.unifiedreader.ui.components.AppSegmentedRow
import com.example.unifiedreader.ui.components.AppSettingsRow
import com.example.unifiedreader.ui.components.AppSliderRow
import com.example.unifiedreader.ui.components.AppSwitch
import com.example.unifiedreader.ui.theme.AppMotion
import java.time.LocalTime
import kotlinx.coroutines.delay

/**
 * The Light card, from the reading settings: brightness (the tablet's, or the reader's own level),
 * a warm tint over the page (all the time, or from 21:00) and keeping the screen on. A small card
 * under the top bar that leaves the page undimmed, so what it changes shows as it changes.
 */
@Composable
internal fun EpubLightCard(
    isOpen: Boolean,
    slot: ReaderPanelSlot,
    comfort: EpubReaderComfort,
    onDismissRequest: () -> Unit,
) {
    val saved = comfort.comfort
    ReaderPanel(
        visible = isOpen,
        placement = AppFloatingPanelPlacement.TopEnd,
        width = ReaderChromeDefaults.contentsWidth,
        slot = slot,
        onDismissRequest = onDismissRequest,
        testTag = EpubLightCardTag,
    ) {
        AppPanelHeader(title = EpubLightCopy.Title, onClose = onDismissRequest)
        AppSliderRow(
            label = EpubLightCopy.Brightness,
            value = comfort.shownBrightness,
            onValueChange = comfort::previewBrightness,
            onValueChangeFinished = comfort::commit,
            valueLabel = if (saved.ownBrightness) epubBrightnessLabel(comfort.shownBrightness) else EpubLightCopy.Tablet,
        )
        AppSegmentedRow(modifier = Modifier.fillMaxWidth().padding(horizontal = AppLayout.inset)) {
            AppSegment(
                selected = !saved.ownBrightness,
                onClick = { comfort.followTablet(true) },
                label = { Text(text = EpubLightCopy.FollowTablet, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
            AppSegment(
                selected = saved.ownBrightness,
                onClick = { comfort.followTablet(false) },
                label = { Text(text = EpubLightCopy.OwnLevel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
        Text(
            text = EpubLightCopy.SwipeHint,
            style = MaterialTheme.typography.labelMedium,
            color = readerOverlayTokens().colors.secondaryText,
            modifier = Modifier.padding(horizontal = AppLayout.inset, vertical = AppLayout.headingGap),
        )
        AppSliderRow(
            label = EpubLightCopy.Warmth,
            value = saved.warmth,
            onValueChange = comfort::previewWarmth,
            onValueChangeFinished = comfort::commit,
            valueLabel = epubWarmthLabel(saved),
        )
        EpubLightSwitchRow(
            title = EpubLightCopy.NightOnly,
            supporting = EpubLightCopy.NightOnlySupporting,
            checked = saved.warmthAtNightOnly,
            enabled = saved.warmth > 0f,
            onCheckedChange = { nightOnly -> comfort.change { it.copy(warmthAtNightOnly = nightOnly) } },
        )
        EpubLightSwitchRow(
            title = EpubLightCopy.KeepScreenOn,
            supporting = EpubLightCopy.KeepScreenOnSupporting,
            checked = saved.keepScreenOn,
            onCheckedChange = { keep -> comfort.change { it.copy(keepScreenOn = keep) } },
        )
    }
}

@Composable
private fun EpubLightSwitchRow(
    title: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    AppSettingsRow(
        title = title,
        supporting = supporting,
        onClick = { onCheckedChange(!checked) },
        enabled = enabled,
        showChevron = false,
        trailing = { AppSwitch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

/** "Light    40%, warm ›" in the reading settings' Page section: opens the Light card. */
@Composable
internal fun EpubLightRow(
    comfort: EpubReaderComfort,
    onClick: () -> Unit,
) {
    val tokens = readerOverlayTokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = AppLayout.inset, vertical = EpubLightRowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EpubLightRowGap),
    ) {
        Icon(imageVector = AppIcons.LightMode, contentDescription = null, tint = tokens.colors.secondaryText)
        Text(
            text = EpubLightCopy.Title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = tokens.colors.primaryText,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = epubLightSummary(comfort.comfort),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(imageVector = AppIcons.NavigateNext, contentDescription = null, tint = tokens.colors.tertiaryText)
    }
}

/**
 * The warm tint over the page: the page's colours multiplied by a warm white, so black stays black and
 * white turns amber. Drawn straight over the page, never in a layer of its own (a layer has nothing
 * under it to multiply with). With the night-only choice it looks at the clock once a minute.
 */
@Composable
internal fun EpubWarmthTint(comfort: EpubReaderComfort) {
    val saved = comfort.comfort
    var minuteOfDay by remember { mutableIntStateOf(currentMinuteOfDay()) }
    val watchesClock = saved.warmth > 0f && saved.warmthAtNightOnly
    LaunchedEffect(watchesClock) {
        while (watchesClock) {
            minuteOfDay = currentMinuteOfDay()
            delay(EpubClockTickMillis)
        }
    }
    val warmth = saved.warmthAt(minuteOfDay)
    if (warmth <= 0f) return
    val tint = epubWarmthColor(warmth)
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(color = tint, blendMode = BlendMode.Multiply)
    }
}

/** The multiplier for [warmth]: white at none, [EpubWarmestTint] at full. */
internal fun epubWarmthColor(warmth: Float): Color = lerp(Color.White, EpubWarmestTint, warmth.coerceIn(0f, 1f))

/**
 * The slim bar and the level by the left edge while a swipe changes the brightness, fading out
 * a moment after the finger lifts.
 */
@Composable
internal fun BoxScope.EpubBrightnessIndicator(comfort: EpubReaderComfort) {
    val swipeLevel = if (comfort.isSwiping) comfort.shownBrightness else null
    var shownLevel by remember { mutableFloatStateOf(swipeLevel ?: 0f) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(swipeLevel) {
        if (swipeLevel != null) {
            shownLevel = swipeLevel
            visible = true
        } else if (visible) {
            delay(EpubBrightnessIndicatorLingerMillis)
            visible = false
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(AppMotion.effects()),
        exit = fadeOut(AppMotion.effects()),
        modifier = Modifier
            .align(Alignment.CenterStart)
            .padding(start = ReaderChromeDefaults.inset),
    ) {
        EpubBrightnessGauge(level = shownLevel)
    }
}

@Composable
private fun EpubBrightnessGauge(level: Float) {
    val tokens = readerOverlayTokens()
    BoxWithConstraints {
        val barHeight = EpubBrightnessBarHeight.coerceAtMost(maxHeight * EpubBrightnessBarMaxShare)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppLayout.menuInset),
        ) {
            Box(
                modifier = Modifier
                    .width(EpubBrightnessBarWidth)
                    .height(barHeight)
                    .clip(CircleShape)
                    .background(tokens.colors.container.copy(alpha = EpubBrightnessTrackAlpha)),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(level.coerceIn(0f, 1f))
                        .clip(CircleShape)
                        .background(tokens.colors.accent),
                )
            }
            Row(
                modifier = Modifier
                    .clip(tokens.shapes.button)
                    .background(tokens.colors.container)
                    .padding(horizontal = AppLayout.headingGap, vertical = AppLayout.headingGap),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
            ) {
                Icon(
                    imageVector = AppIcons.LightMode,
                    contentDescription = null,
                    tint = tokens.colors.primaryText,
                    modifier = Modifier.size(EpubBrightnessIconSize),
                )
                Text(
                    text = epubBrightnessLabel(level),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.colors.primaryText,
                )
            }
        }
    }
}

/** The Light row's value: "Tablet", "40%", "Tablet, warm from 21:00". */
internal fun epubLightSummary(comfort: ReaderComfort): String {
    val brightness = comfort.brightness?.takeIf { comfort.ownBrightness }?.let(::epubBrightnessLabel) ?: EpubLightCopy.Tablet
    val warmth = when {
        comfort.warmth <= 0f -> null
        comfort.warmthAtNightOnly -> "warm from 21:00"
        else -> "warm"
    }
    return listOfNotNull(brightness, warmth).joinToString(", ")
}

/** The warmth slider's value: "Off", "55%", "55% from 21:00". */
internal fun epubWarmthLabel(comfort: ReaderComfort): String = when {
    comfort.warmth <= 0f -> "Off"
    comfort.warmthAtNightOnly -> "${epubBrightnessLabel(comfort.warmth)} from 21:00"
    else -> epubBrightnessLabel(comfort.warmth)
}

private fun currentMinuteOfDay(): Int = LocalTime.now().let { it.hour * MinutesPerHour + it.minute }

internal object EpubLightCopy {
    const val Title = "Light"
    const val Brightness = "Brightness"
    const val Tablet = "Tablet"
    const val FollowTablet = "Follow the tablet"
    const val OwnLevel = "Own for reading"
    const val SwipeHint = "Or slide a finger up or down the left edge of the page."
    const val Warmth = "Warmth"
    const val NightOnly = "Only from 21:00"
    const val NightOnlySupporting = "Off again at 6:00"
    const val KeepScreenOn = "Keep the screen on"
    const val KeepScreenOnSupporting = "While a book is open, until 10 minutes without a touch"
}

/** Full warmth: blue down to 42%, green to 72%, like a tablet's night light at its warmest. */
private val EpubWarmestTint = Color(red = 1f, green = 0.72f, blue = 0.42f)
private val EpubLightRowVerticalPadding = 12.dp
private val EpubLightRowGap = 12.dp
private val EpubBrightnessBarWidth = 6.dp
private val EpubBrightnessBarHeight = 320.dp
private val EpubBrightnessIconSize = 18.dp
private const val EpubBrightnessBarMaxShare = 0.5f
private const val EpubBrightnessTrackAlpha = 0.7f
private const val EpubBrightnessIndicatorLingerMillis = 700L
private const val EpubClockTickMillis = 60_000L
private const val MinutesPerHour = 60

internal const val EpubLightCardTag = "epub-light-card"
