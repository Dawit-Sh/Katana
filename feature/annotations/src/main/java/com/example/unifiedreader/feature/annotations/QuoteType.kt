package com.example.unifiedreader.feature.annotations

import android.content.Context
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * Literata, the reader's book face, for quotes and words outside the book. The reader bundles it
 * (feature:reader-epub's assets); without it the system serif stands in.
 */
@Composable
fun rememberQuoteFontFamily(): FontFamily {
    val context = LocalContext.current
    return remember(context) {
        if (context.hasAsset(LiterataRegular) && context.hasAsset(LiterataItalic)) {
            FontFamily(
                Font(LiterataRegular, context.assets, weight = FontWeight.Normal),
                Font(LiterataRegular, context.assets, weight = FontWeight.Bold),
                Font(LiterataItalic, context.assets, weight = FontWeight.Normal, style = FontStyle.Italic),
            )
        } else {
            FontFamily.Serif
        }
    }
}

/** Literata as a platform typeface, [italic] or upright, for drawing on a bitmap. */
internal fun Context.quoteTypeface(italic: Boolean, bold: Boolean = false): Typeface {
    val path = if (italic) LiterataItalic else LiterataRegular
    val base = if (hasAsset(path)) {
        Typeface.Builder(assets, path)
            .setFontVariationSettings("'wght' ${if (bold) BOLD_WEIGHT else REGULAR_WEIGHT}")
            .build()
    } else {
        null
    }
    return base ?: Typeface.create(Typeface.SERIF, if (italic) Typeface.ITALIC else Typeface.NORMAL)
}

private fun Context.hasAsset(path: String): Boolean = try {
    assets.open(path).close()
    true
} catch (_: java.io.IOException) {
    false
}

private const val LiterataRegular = "fonts/literata/Literata-Variable.ttf"
private const val LiterataItalic = "fonts/literata/Literata-Italic-Variable.ttf"
private const val REGULAR_WEIGHT = 400
private const val BOLD_WEIGHT = 700
