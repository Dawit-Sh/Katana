package com.example.unifiedreader.feature.annotations

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A highlight's colour and style as a server keeps them: Grimmory stores `#RRGGBB` and one of
 * `highlight`, `underline`, `strikethrough`, `squiggly`, the same four styles as Katana.
 *
 * Katana's five colours go up as the five of Grimmory's web reader (yellow, green, blue, pink,
 * orange), so a highlight shows there in the colour of the same name. A colour coming down is the
 * Katana colour of that name when it is one of the web reader's, KOReader's (through the
 * grimmory.koplugin) or Katana's own; any other is the Katana colour nearest in hue.
 */
fun HighlightColor.toServerHex(): String = when (this) {
    HighlightColor.Yellow -> "#FACC15"
    HighlightColor.Green -> "#4ADE80"
    HighlightColor.Blue -> "#38BDF8"
    HighlightColor.Pink -> "#F472B6"
    HighlightColor.Orange -> "#FB923C"
}

fun highlightColorFromServer(hex: String?): HighlightColor {
    val rgb = hex?.trim()?.removePrefix("#")?.takeIf { it.length == HEX_DIGITS }?.toIntOrNull(HEX_RADIX)
    return rgb?.let { KnownColors[it] ?: nearestInHue(it) } ?: HighlightColor.Default
}

val HighlightStyle.serverName: String
    get() = when (this) {
        HighlightStyle.Highlight -> "highlight"
        HighlightStyle.Underline -> "underline"
        HighlightStyle.Strikethrough -> "strikethrough"
        HighlightStyle.Squiggly -> "squiggly"
    }

fun highlightStyleFromServer(name: String?): HighlightStyle =
    HighlightStyle.entries.firstOrNull { style -> style.serverName.equals(name?.trim(), ignoreCase = true) }
        ?: HighlightStyle.Default

private val KnownColors: Map<Int, HighlightColor> = buildMap {
    // Grimmory's web reader: its five, its darker five, and the colour it gives when none is sent.
    put(0xFACC15, HighlightColor.Yellow)
    put(0x4ADE80, HighlightColor.Green)
    put(0x38BDF8, HighlightColor.Blue)
    put(0xF472B6, HighlightColor.Pink)
    put(0xFB923C, HighlightColor.Orange)
    put(0xB8860B, HighlightColor.Yellow)
    put(0x228B22, HighlightColor.Green)
    put(0x1E90FF, HighlightColor.Blue)
    put(0xDC143C, HighlightColor.Pink)
    put(0xFF8C00, HighlightColor.Orange)
    put(0xFFFF00, HighlightColor.Yellow)
    // KOReader through the grimmory.koplugin (its green, cyan, pink and orange are the web reader's).
    put(0xFFC107, HighlightColor.Yellow)
    put(0xFB523C, HighlightColor.Orange)
    put(0xF452FC, HighlightColor.Pink)
    put(0x0248F8, HighlightColor.Blue)
    put(0xAAAAAA, HighlightColor.Yellow)
    put(0xFAFAFA, HighlightColor.Yellow)
    // Katana's own, should another client keep them.
    HighlightColor.entries.forEach { color -> put((color.argb and RGB_MASK).toInt(), color) }
}

private fun nearestInHue(rgb: Int): HighlightColor {
    val hue = hueOf(rgb) ?: return HighlightColor.Default
    return HighlightColor.entries.minBy { color ->
        val other = hueOf((color.argb and RGB_MASK).toInt()) ?: return@minBy Double.MAX_VALUE
        val distance = abs(hue - other)
        min(distance, FULL_TURN - distance)
    }
}

// The hue in degrees, or null for a grey (no colour to match).
private fun hueOf(rgb: Int): Double? {
    val r = (rgb shr RED_SHIFT and CHANNEL_MASK) / CHANNEL_MAX
    val g = (rgb shr GREEN_SHIFT and CHANNEL_MASK) / CHANNEL_MAX
    val b = (rgb and CHANNEL_MASK) / CHANNEL_MAX
    val high = max(r, max(g, b))
    val low = min(r, min(g, b))
    val chroma = high - low
    if (chroma < GREY_CHROMA) return null
    val sector = when (high) {
        r -> ((g - b) / chroma).mod(SECTORS)
        g -> (b - r) / chroma + GREEN_SECTOR
        else -> (r - g) / chroma + BLUE_SECTOR
    }
    return sector * SECTOR_DEGREES
}

private const val HEX_DIGITS = 6
private const val HEX_RADIX = 16
private const val RGB_MASK = 0xFFFFFFL
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val CHANNEL_MASK = 0xFF
private const val CHANNEL_MAX = 255.0
private const val GREEN_SECTOR = 2.0
private const val BLUE_SECTOR = 4.0
private const val GREY_CHROMA = 0.12
private const val SECTORS = 6.0
private const val SECTOR_DEGREES = 60.0
private const val FULL_TURN = 360.0
