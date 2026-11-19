package com.example.unifiedreader.feature.annotations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.unifiedreader.ui.components.AppDialog
import com.example.unifiedreader.ui.components.AppDialogConfirmButton
import com.example.unifiedreader.ui.components.AppDialogDismissButton
import com.example.unifiedreader.ui.components.AppLayout
import com.example.unifiedreader.ui.components.AppTextField

/**
 * A bookmark's name and note: two fields in an `AppDialog`, the name on one line ("Page 8 of 78"
 * shows while it is empty), the note under it. Save keeps both; an emptied field removes it.
 */
@Composable
fun BookmarkNameDialog(
    bookmark: ReaderBookmark,
    onDismiss: () -> Unit,
    onSave: (title: String, note: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var title by rememberSaveable(bookmark.id) { mutableStateOf(bookmark.title.orEmpty()) }
    var note by rememberSaveable(bookmark.id) { mutableStateOf(bookmark.note.orEmpty()) }
    AppDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(BookmarkNamingCopy.Title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppLayout.headingGap)) {
                AppTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = { Text(AnnotationMarkdown.bookmarkPlace(bookmark)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                AppTextField(
                    value = note,
                    onValueChange = { note = it },
                    placeholder = { Text(BookmarkNamingCopy.NotePlaceholder) },
                    minLines = NOTE_LINES,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        dismissButton = { AppDialogDismissButton(text = BookmarksCopy.Cancel, onClick = onDismiss) },
        confirmButton = { AppDialogConfirmButton(text = BookmarksCopy.Save, onClick = { onSave(title, note) }) },
    )
}

/** What naming a bookmark says. */
object BookmarkNamingCopy {
    const val Title = "Name and note"
    const val Edit = "Name or note"
    const val NotePlaceholder = "A note, if you like"
}

/** A bookmark's first line in a list: its name, or "Page 8 of 78 · 9%". */
fun bookmarkHeadline(bookmark: ReaderBookmark): String = bookmark.title ?: bookmarkTitle(bookmark)

/** Its second line: the page (when the name took the first) and the first words or the chapter. */
fun bookmarkSubline(bookmark: ReaderBookmark): String? = listOfNotNull(
    bookmark.title?.let { AnnotationMarkdown.bookmarkPlace(bookmark) },
    bookmark.text ?: chapterOf(bookmark.place.chapterTitle, bookmark.book),
).joinToString(" · ").takeIf(String::isNotEmpty)

private const val NOTE_LINES = 2
