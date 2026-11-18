package com.example.unifiedreader.core.model

import java.util.Locale

/**
 * A size on disk as the app writes it: "640 KB", "2.1 MB", "310 MB", "1.5 GB". One decimal below
 * ten of a unit, whole numbers above. Units of 1000, as Android's own storage settings count.
 */
fun formatStorageSize(bytes: Long): String {
    val size = bytes.coerceAtLeast(0L).toDouble()
    val (value, unit) = when {
        size >= GIGA -> size / GIGA to "GB"
        size >= MEGA -> size / MEGA to "MB"
        size >= KILO -> size / KILO to "KB"
        else -> return "${bytes.coerceAtLeast(0L)} B"
    }
    val digits = if (value < TEN) 1 else 0
    return String.format(Locale.ROOT, "%.${digits}f %s", value, unit)
}

private const val KILO = 1_000.0
private const val MEGA = 1_000_000.0
private const val GIGA = 1_000_000_000.0
private const val TEN = 9.95
