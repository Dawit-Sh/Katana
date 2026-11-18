package com.example.unifiedreader.feature.reader.epub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.unifiedreader.ui.components.AppLayout
import com.example.unifiedreader.ui.components.AppTonalButton

/**
 * The settings "Use these for new books" keeps: the text and the page turn. Page colour and
 * layout are left out; they have their own defaults (the last colour picked, the orientation).
 */
internal val EpubNewBookLookKeys: Set<String> = setOf(
    EpubTypographyModeKey,
    EpubFontFamilyKey,
    EpubFontSizeKey,
    EpubLineHeightKey,
    EpubPageMarginsKey,
    EpubParagraphSpacingKey,
    EpubParagraphIndentKey,
    EpubTextAlignKey,
    EpubPageTurnStyleKey,
)

/** This book's look, as "Use these for new books" saves it. */
internal fun EpubReaderPreferences.newBookLook(): Map<String, String> =
    buildEpubPreferencesMap(this).filterKeys(EpubNewBookLookKeys::contains)

/**
 * The settings a book opens with: its own once it has any, else the look saved for new books.
 */
internal fun epubOpeningPreferences(
    bookPreferences: Map<String, String>,
    newBookLook: Map<String, String>,
): Map<String, String> = bookPreferences.ifEmpty { newBookLook.filterKeys(EpubNewBookLookKeys::contains) }

internal object EpubNewBookLookCopy {
    const val Button = "Use these for new books"
    const val Saved = "Saved for new books"
    const val Note = "Books you haven't opened yet start with this font, size and spacing."
}

@Composable
internal fun EpubUseForNewBooksRow(onUseForNewBooks: () -> Unit) {
    var saved by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppLayout.inset)
            .padding(top = AppLayout.headingGap),
        verticalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
    ) {
        AppTonalButton(
            onClick = {
                onUseForNewBooks()
                saved = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (saved) EpubNewBookLookCopy.Saved else EpubNewBookLookCopy.Button)
        }
        Text(
            text = EpubNewBookLookCopy.Note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
