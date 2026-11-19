package com.example.unifiedreader.feature.reader.comics

import androidx.compose.runtime.Composable
import com.example.unifiedreader.feature.reader.core.ReaderTapSide
import com.example.unifiedreader.feature.reader.core.ReaderVolumeKeys

/**
 * Settings > Reading > "Volume keys turn pages" in a paged comic: the keys turn as the page's sides do,
 * mirrored in a right-to-left book ([comicVolumeKeyAction]). [currentSpread] is read at each press.
 */
@Composable
internal fun ComicPagedVolumeKeys(
    enabled: Boolean,
    readingDirection: ComicReadingDirection,
    currentSpread: () -> Int,
    onPrevious: (Int) -> Unit,
    onNext: (Int) -> Unit,
) {
    ReaderVolumeKeys(enabled = enabled) { side ->
        when (comicVolumeKeyAction(side, readingDirection)) {
            ReaderTapAction.Previous -> onPrevious(currentSpread())
            ReaderTapAction.Next -> onNext(currentSpread())
            ReaderTapAction.ToggleOverlay, null -> Unit
        }
    }
}

/** In a scrolling comic the keys go a page down or up the strip, whichever way it runs. */
@Composable
internal fun ComicStripVolumeKeys(
    enabled: Boolean,
    readerState: ComicsReaderState,
    pageCount: Int,
) {
    ReaderVolumeKeys(enabled = enabled) { side ->
        val step = when (side) {
            ReaderTapSide.Left -> -1
            ReaderTapSide.Right -> 1
            ReaderTapSide.Middle -> 0
        }
        val target = readerState.currentPage + step
        if (step != 0 && target in 0 until pageCount) {
            readerState.onContinuousPositionSettled(pageIndex = target, offsetRatio = 0f)
        }
    }
}
