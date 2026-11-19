package com.example.unifiedreader.feature.reader.epub

import android.app.Activity
import com.example.unifiedreader.feature.reader.core.ReaderTapSide
import com.example.unifiedreader.feature.reader.core.readerVolumeKeySide
import com.example.unifiedreader.feature.reader.core.turnReaderPagesWithVolumeKeys

/**
 * The side of the page a volume key stands for (see [readerVolumeKeySide]). Through
 * [resolveEpubTapAction] a right-to-left book mirrors them, as it does taps.
 */
internal fun epubVolumeKeySide(keyCode: Int): ReaderTapSide? = readerVolumeKeySide(keyCode)

/** The EPUB reader's volume keys: [turnReaderPagesWithVolumeKeys], shared with the other readers. */
internal fun Activity.turnPagesWithVolumeKeys(onPageKey: (ReaderTapSide) -> Unit): () -> Unit =
    turnReaderPagesWithVolumeKeys(onPageKey)
