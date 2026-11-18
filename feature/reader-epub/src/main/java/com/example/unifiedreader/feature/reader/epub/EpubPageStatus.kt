package com.example.unifiedreader.feature.reader.epub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import java.util.Date
import kotlinx.coroutines.delay

private const val MillisPerMinute = 60_000L
private const val PercentScale = 100

/** The corner's words: the time, then the battery, "20:17 · 84%". */
internal fun epubPageStatusLabel(time: String, batteryPercent: Int?): String =
    listOfNotNull(time, batteryPercent?.let { "$it%" }).joinToString(" · ")

/**
 * The time and the battery at the bottom strip's inner corner, opposite the pages left in the
 * chapter: the start one, the end one in a right-to-left book.
 */
@Composable
internal fun BoxScope.EpubPageStatus(
    color: Color,
    height: Dp,
    isRightToLeft: Boolean,
) {
    val context = LocalContext.current
    val time by produceState(initialValue = formatEpubStatusTime(context)) {
        while (true) {
            // Wakes on the next minute, so the clock turns over with the system's.
            delay(MillisPerMinute - System.currentTimeMillis() % MillisPerMinute)
            value = formatEpubStatusTime(context)
        }
    }
    val battery = rememberBatteryPercent(context)
    Text(
        text = epubPageStatusLabel(time, battery),
        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
        color = color,
        maxLines = 1,
        textAlign = if (isRightToLeft) TextAlign.Right else TextAlign.Left,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(height)
            .wrapContentHeight(Alignment.CenterVertically)
            .padding(horizontal = EpubPageHeadSideInset)
            .testTag(EpubPageStatusTag),
    )
}

private fun formatEpubStatusTime(context: Context): String =
    DateFormat.getTimeFormat(context).format(Date())

/** The battery level in percent, kept current while the page shows; null if the tablet doesn't say. */
@Composable
private fun rememberBatteryPercent(context: Context): Int? {
    var percent by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                percent = intent.batteryPercent()
            }
        }
        // The battery broadcast is sticky: registering hands back the current level at once.
        val current = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        percent = current?.batteryPercent()
        onDispose { context.unregisterReceiver(receiver) }
    }
    return percent
}

private fun Intent.batteryPercent(): Int? {
    val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    return if (level < 0 || scale <= 0) null else level * PercentScale / scale
}

internal const val EpubPageStatusTag = "epub-page-status"
