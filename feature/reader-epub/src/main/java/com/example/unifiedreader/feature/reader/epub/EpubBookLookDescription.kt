package com.example.unifiedreader.feature.reader.epub

import com.example.unifiedreader.feature.reader.core.ReaderSessionSnapshot
import com.example.unifiedreader.feature.reader.preferences.ReaderBookLookDescription
import com.example.unifiedreader.feature.reader.preferences.ReaderBookLookLine
import com.example.unifiedreader.feature.reader.preferences.ReaderPagesLabel

/**
 * The look "Use these for new books" saved, in the Aa panel's words, for Settings ›
 * Default look for new books. Nothing saved describes as [ReaderBookLookDescription.NotSaved].
 */
fun describeEpubBookLook(values: Map<String, String>): ReaderBookLookDescription {
    val look = values.filterKeys(EpubNewBookLookKeys::contains)
    if (look.isEmpty()) return ReaderBookLookDescription.NotSaved
    val preferences = restoreEpubPreferences(session = ReaderSessionSnapshot(itemId = "", preferences = look))
    val pages = pagesLines(preferences) + ReaderBookLookLine("Page turn", preferences.pageTurnStyle.label)
    return if (preferences.typographyMode == EpubTypographyMode.Original) {
        val typography = epubTypographyModeLabel(EpubTypographyMode.Original)
        ReaderBookLookDescription(
            summary = "$typography font and spacing",
            lines = listOf(ReaderBookLookLine("Typography", typography)) + pages,
        )
    } else {
        customLookDescription(preferences, pages)
    }
}

/** Pages, Turn or Scroll, and Two pages when it is set to Off or On rather than Auto. */
private fun pagesLines(preferences: EpubReaderPreferences): List<ReaderBookLookLine> {
    val followsOrientation = !preferences.layoutModeOverride
    val pages = epubPagesMode(followsOrientation, preferences.layoutMode)
    val spread = epubSpreadChoice(followsOrientation, preferences.layoutMode)?.takeUnless { it == EpubSpreadChoice.Auto }
    return listOfNotNull(
        ReaderBookLookLine(ReaderPagesLabel, pages.label),
        spread?.let { ReaderBookLookLine("Two pages", it.label) },
    )
}

private fun customLookDescription(
    preferences: EpubReaderPreferences,
    pages: List<ReaderBookLookLine>,
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
        ) + pages,
    )
}
