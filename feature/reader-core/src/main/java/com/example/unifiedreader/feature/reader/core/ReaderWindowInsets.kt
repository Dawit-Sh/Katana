package com.example.unifiedreader.feature.reader.core

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.safeGestures
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable

/**
 * What the reader's bars keep clear of while the system bars are hidden: the gesture areas at the
 * edges and the display cutout (a phone's camera hole or notch, also beside the page when the phone
 * is turned sideways). The cutout is nothing on the Fire 7, so the bars stay where they were there.
 */
val WindowInsets.Companion.readerChromeSafe: WindowInsets
    @Composable
    @NonRestartableComposable
    get() = safeGestures.union(displayCutout)
