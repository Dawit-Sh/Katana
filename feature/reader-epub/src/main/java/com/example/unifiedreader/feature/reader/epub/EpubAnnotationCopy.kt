package com.example.unifiedreader.feature.reader.epub

import com.example.unifiedreader.feature.annotations.BookMarksPanelCopy
import com.example.unifiedreader.feature.annotations.BookmarksCopy
import com.example.unifiedreader.feature.annotations.HighlightColor
import com.example.unifiedreader.feature.annotations.HighlightCopy
import com.example.unifiedreader.feature.annotations.HighlightStyle

/** What the reader's highlights, bookmarks, notes and saved words say on screen. */
internal object EpubAnnotationCopy {
    const val Highlight = HighlightCopy.Highlight
    const val Note = HighlightCopy.Note
    const val AddToDictionary = "Add to dictionary"
    const val Copy = HighlightCopy.Copy
    const val Delete = HighlightCopy.Delete
    const val EditNote = HighlightCopy.EditNote
    const val AddNote = HighlightCopy.AddNote
    const val NoteTitle = HighlightCopy.NoteTitle
    const val NotePlaceholder = HighlightCopy.NotePlaceholder
    const val Save = HighlightCopy.Save
    const val Cancel = HighlightCopy.Cancel
    const val Panel = BookMarksPanelCopy.Panel
    const val PanelButton = BookMarksPanelCopy.PanelButton
    const val HighlightsSection = BookMarksPanelCopy.HighlightsSection
    const val WordsSection = BookMarksPanelCopy.WordsSection
    const val ExportHighlights = BookMarksPanelCopy.ExportHighlights
    const val Empty = "Bookmark a page with the ribbon button, or select words, then Highlight or Add to dictionary."
    const val SearchMarks = BookMarksPanelCopy.SearchMarks
    const val NothingFound = BookMarksPanelCopy.NothingFound
    const val AddBookmark = "Bookmark this page"
    const val RemoveBookmark = "Remove this page's bookmark"
    const val Bookmarked = "Page bookmarked"
    const val BookmarkRemoved = "Bookmark removed"
    const val ShareImage = HighlightCopy.ShareImage
    const val LookUp = BookMarksPanelCopy.LookUp
    const val Removed = HighlightCopy.Removed
    const val Undo = "Undo"
    const val WordRemoved = "Word removed"
    const val Exported = BookMarksPanelCopy.Exported
    const val ExportFailed = BookMarksPanelCopy.ExportFailed

    fun wordSaved(word: String): String = "“$word” added to Saved words"

    fun count(highlights: Int, words: Int, bookmarks: Int = 0): String = BookMarksPanelCopy.count(highlights, words, bookmarks)

    fun style(style: HighlightStyle): String = BookmarksCopy.style(style)

    fun color(color: HighlightColor): String = HighlightCopy.color(color)
}
