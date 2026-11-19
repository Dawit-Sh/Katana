package com.example.unifiedreader.feature.reader.epub

import com.example.unifiedreader.feature.annotations.BookmarksCopy
import com.example.unifiedreader.feature.annotations.HighlightColor
import com.example.unifiedreader.feature.annotations.MarkCounts
import com.example.unifiedreader.feature.annotations.HighlightStyle

/** What the reader's highlights, bookmarks, notes and saved words say on screen. */
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
    const val Panel = "Marks in this book"
    const val PanelButton = "Highlights, bookmarks and words"
    const val HighlightsSection = "Highlights"
    const val WordsSection = "Saved words"
    const val ExportHighlights = "Export highlights"
    const val Empty = "Bookmark a page with the ribbon button, or select words, then Highlight or Add to dictionary."
    const val SearchMarks = "Search this book's marks"
    const val NothingFound = "Nothing in this book's marks matches."
    const val AddBookmark = "Bookmark this page"
    const val RemoveBookmark = "Remove this page's bookmark"
    const val Bookmarked = "Page bookmarked"
    const val BookmarkRemoved = "Bookmark removed"
    const val ShareImage = "Share as an image"
    const val LookUp = "Look up"
    const val Removed = "Highlight removed"
    const val Undo = "Undo"
    const val WordRemoved = "Word removed"
    const val Exported = "Exported"
    const val ExportFailed = "Couldn't save the file"

    fun wordSaved(word: String): String = "“$word” added to Saved words"

    fun count(highlights: Int, words: Int, bookmarks: Int = 0): String = listOf(
        MarkCounts.highlights(highlights),
        MarkCounts.bookmarks(bookmarks),
        MarkCounts.words(words),
    ).joinToString(" · ")

    fun style(style: HighlightStyle): String = BookmarksCopy.style(style)

    fun color(color: HighlightColor): String = when (color) {
        HighlightColor.Yellow -> "Yellow"
        HighlightColor.Green -> "Green"
        HighlightColor.Blue -> "Blue"
        HighlightColor.Pink -> "Pink"
        HighlightColor.Orange -> "Orange"
    }
}
