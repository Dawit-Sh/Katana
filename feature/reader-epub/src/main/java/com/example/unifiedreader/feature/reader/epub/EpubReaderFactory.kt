package com.example.unifiedreader.feature.reader.epub

import android.net.Uri
import com.example.unifiedreader.core.model.EpubReaderFormats
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.feature.annotations.ReaderAnnotationStore
import com.example.unifiedreader.feature.reader.core.ReaderChrome
import com.example.unifiedreader.feature.reader.core.ReaderScreenFactory
import com.example.unifiedreader.feature.reader.core.ReaderSessionStore
import com.example.unifiedreader.feature.reader.core.formatReaderFactory
import com.example.unifiedreader.feature.reader.preferences.ReaderDisplayPreferences
import com.example.unifiedreader.feature.translation.TranslationService
import com.example.unifiedreader.journal.JournalReadingSessionRecorder

fun epubReaderFactory(
    sessionStore: ReaderSessionStore,
    journalReadingSessionRecorder: JournalReadingSessionRecorder? = null,
    documentUriProvider: suspend (ReadableItem) -> Uri? = { null },
    displayPreferences: ReaderDisplayPreferences = ReaderDisplayPreferences(),
    extras: EpubReaderExtras = EpubReaderExtras(),
): ReaderScreenFactory = formatReaderFactory(
    // EPUB, and the MOBI, AZW, AZW3 and FB2 books it reads as the EPUBs Katana makes from them.
    formats = EpubReaderFormats,
    chrome = ReaderChrome.Immersive,
) { item, onBack, navigationActions ->
        EpubReaderScreen(
            item = item,
            sessionStore = sessionStore,
            journalReadingSessionRecorder = journalReadingSessionRecorder,
            documentUriProvider = documentUriProvider,
            displayPreferences = displayPreferences,
            onBack = onBack,
            onCompletionRequested = navigationActions.onRequestCompletion,
            translation = extras.translation,
            annotationStore = extras.annotations,
        )
}

/** What the EPUB reader can add when the app has it: translation of a selection, and highlights and saved words. */
data class EpubReaderExtras(
    val translation: TranslationService? = null,
    val annotations: ReaderAnnotationStore? = null,
)
