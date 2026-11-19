package com.example.unifiedreader.feature.library

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf

/**
 * Pull down on the library's grid to sync it with its server: [onPull] when let go past the threshold,
 * the indicator turning while [refreshing]. Provided around a server's library; none (the default) on
 * the books on this device, which have nothing to fetch.
 */
@Immutable
class LibraryPullToRefresh(
    val refreshing: Boolean,
    val onPull: () -> Unit,
)

val LocalLibraryPullToRefresh = compositionLocalOf<LibraryPullToRefresh?> { null }
