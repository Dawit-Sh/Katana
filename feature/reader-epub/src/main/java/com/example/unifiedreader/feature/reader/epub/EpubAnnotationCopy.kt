package com.example.unifiedreader.feature.reader.epub

import com.example.unifiedreader.feature.annotations.HighlightColor
import com.example.unifiedreader.feature.annotations.HighlightStyle

/** What the reader's highlights, notes and saved words say on screen. */
internal object EpubAnnotationCopy {
    const val Highlight = "Highlight"
    const val Note = "Note"
    const val AddToDictionary = "Add to dictionary"
    const val Copy = "Copy"
    const val Delete = "Delete"
    const val EditNote = "Edit note"
    const val AddNote = "Add note"
    const val NoteTitle = "Note"
    const val NotePlaceholder = "What this makes you think"
    const val Save = "Save"
    const val Cancel = "Cancel"
    const val Panel = "Highlights & words"
    const val PanelButton = "Highlights and saved words"
    const val HighlightsSection = "Highlights"
    const val WordsSection = "Saved words"
    const val Export = "Export"
    const val ExportBook = "This book (Markdown)"
    const val ExportAll = "Every book (Markdown)"
    const val Empty = "Select words in the book, then Highlight or Add to dictionary."
    const val LookUp = "Look up"
    const val Removed = "Highlight removed"
    const val Undo = "Undo"
    const val WordRemoved = "Word removed"
    const val Exported = "Exported"
    const val ExportFailed = "Couldn't save the file"

    fun wordSaved(word: String): String = "“$word” saved to My words"

    fun count(highlights: Int, words: Int): String = listOf(
        if (highlights == 1) "1 highlight" else "$highlights highlights",
        if (words == 1) "1 word" else "$words words",
    ).joinToString(" · ")

    fun style(style: HighlightStyle): String = when (style) {
        HighlightStyle.Highlight -> "Highlight"
        HighlightStyle.Underline -> "Underline"
        HighlightStyle.Strikethrough -> "Strike"
        HighlightStyle.Squiggly -> "Squiggle"
    }

    fun color(color: HighlightColor): String = when (color) {
        HighlightColor.Yellow -> "Yellow"
        HighlightColor.Green -> "Green"
        HighlightColor.Blue -> "Blue"
        HighlightColor.Pink -> "Pink"
        HighlightColor.Orange -> "Orange"
    }
}
