package com.example.unifiedreader.feature.reader.comics

import com.example.unifiedreader.feature.reader.core.ReaderTapSide
import com.example.unifiedreader.feature.reader.core.readerTapSide

internal enum class ReaderTapAction {
    Previous,
    Next,
    ToggleOverlay,
}

internal fun resolveReaderTapAction(
    tapX: Float,
    containerWidth: Float,
    maxZoneWidth: Float,
    readingDirection: ComicReadingDirection,
    isZoomed: Boolean,
): ReaderTapAction {
    if (containerWidth <= 0f || isZoomed) {
        return ReaderTapAction.ToggleOverlay
    }
    val rightToLeft = readingDirection == ComicReadingDirection.RTL
    return when (readerTapSide(tapX, containerWidth, maxZoneWidth)) {
        ReaderTapSide.Left -> if (rightToLeft) ReaderTapAction.Next else ReaderTapAction.Previous
        ReaderTapSide.Right -> if (rightToLeft) ReaderTapAction.Previous else ReaderTapAction.Next
        ReaderTapSide.Middle -> ReaderTapAction.ToggleOverlay
    }
}

/** What a volume key does in a paged comic: the side it stands for, turned the way a tap there turns. */
internal fun comicVolumeKeyAction(
    side: ReaderTapSide,
    readingDirection: ComicReadingDirection,
): ReaderTapAction? {
    val rightToLeft = readingDirection == ComicReadingDirection.RTL
    return when (side) {
        ReaderTapSide.Left -> if (rightToLeft) ReaderTapAction.Next else ReaderTapAction.Previous
        ReaderTapSide.Right -> if (rightToLeft) ReaderTapAction.Previous else ReaderTapAction.Next
        ReaderTapSide.Middle -> null
    }
}
