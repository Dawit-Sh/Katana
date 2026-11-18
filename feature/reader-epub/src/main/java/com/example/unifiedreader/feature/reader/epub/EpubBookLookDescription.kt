package com.example.unifiedreader.feature.reader.epub

import com.example.unifiedreader.feature.reader.core.ReaderSessionSnapshot
import com.example.unifiedreader.feature.reader.preferences.ReaderBookLookDescription
import com.example.unifiedreader.feature.reader.preferences.ReaderBookLookLine

/**
 * The look "Use these for new books" saved, in the Aa panel's words, for Settings ›
 * Default look for new books. Nothing saved describes as [ReaderBookLookDescription.NotSaved].
 */
fun describeEpubBookLook(values: Map<String, String>): ReaderBookLookDescription {
    val look = values.filterKeys(EpubNewBookLookKeys::contains)
    if (look.isEmpty()) return ReaderBookLookDescription.NotSaved
    val preferences = restoreEpubPreferences(session = ReaderSessionSnapshot(itemId = "", preferences = look))
    val pageTurn = ReaderBookLookLine("Page turn", preferences.pageTurnStyle.label)
    return if (preferences.typographyMode == EpubTypographyMode.Original) {
        val typography = epubTypographyModeLabel(EpubTypographyMode.Original)
        ReaderBookLookDescription(
            summary = "$typography font and spacing",
            lines = listOf(ReaderBookLookLine("Typography", typography), pageTurn),
        )
    } else {
        customLookDescription(preferences, pageTurn)
    }
}

private fun customLookDescription(
    preferences: EpubReaderPreferences,
    pageTurn: ReaderBookLookLine,
): ReaderBookLookDescription {
    val font = preferences.fontFamily.label
    val size = formatEpubFontSizeValue(preferences.fontSize)
    val margins = EpubMarginSteps.nearest(preferences.pageMargins).label
    return ReaderBookLookDescription(
        summary = "$font, $size, $margins margins",
        lines = listOf(
            ReaderBookLookLine("Font", font),
            ReaderBookLookLine("Text size", size),
            ReaderBookLookLine("Line height", formatEpubLineHeight(preferences.lineHeight)),
            ReaderBookLookLine("Alignment", epubTextAlignLabel(preferences.textAlign)),
            ReaderBookLookLine("Margins", margins),
            ReaderBookLookLine("Paragraph spacing", EpubParagraphSpacingSteps.nearest(preferences.paragraphSpacing).label),
            ReaderBookLookLine("Paragraph indent", EpubParagraphIndentSteps.nearest(preferences.paragraphIndent).label),
            pageTurn,
        ),
    )
}
