package com.example.unifiedreader.feature.reader.pdf

import android.net.Uri
import com.example.unifiedreader.core.model.PageImageBookFormats
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.feature.reader.core.ReaderChrome
import com.example.unifiedreader.feature.reader.core.ReaderScreenFactory
import com.example.unifiedreader.feature.reader.core.ReaderSessionStore
import com.example.unifiedreader.feature.reader.core.formatReaderFactory
import com.example.unifiedreader.feature.reader.preferences.ReaderDisplayPreferences
import com.example.unifiedreader.journal.JournalReadingSessionRecorder

fun pdfReaderFactory(
    sessionStore: ReaderSessionStore,
    journalReadingSessionRecorder: JournalReadingSessionRecorder? = null,
    documentUriProvider: suspend (ReadableItem) -> Uri? = { null },
    /**
     * The page number, Settings › Reading's volume keys, and whether a PDF or DjVu book not set yet
     * turns its pages or scrolls (the last pick in any of them).
     */
    displayPreferences: ReaderDisplayPreferences = ReaderDisplayPreferences(),
): ReaderScreenFactory = formatReaderFactory(
    // PDF, and DjVu scans drawn page by page the same way.
    formats = PageImageBookFormats,
    chrome = ReaderChrome.Immersive,
) { item, onBack, navigationActions ->
        PdfReaderScreen(
            item = item,
            documentUriProvider = documentUriProvider,
            sessionStore = sessionStore,
            journalReadingSessionRecorder = journalReadingSessionRecorder,
            pageNumberPreferences = displayPreferences.pageNumbers,
            pagesPreferences = displayPreferences.pages,
            comfortPreferences = displayPreferences.comfort,
            openMode = navigationActions.currentOpenMode,
            onBack = onBack,
            onCompletionRequested = navigationActions.onRequestCompletion,
        )
}
