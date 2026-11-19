package com.example.unifiedreader.feature.annotations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.example.unifiedreader.ui.components.AppBarSwitch
import com.example.unifiedreader.ui.components.AppButton
import com.example.unifiedreader.ui.components.AppIconButton
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppLayout
import com.example.unifiedreader.ui.components.AppTonalButton
import com.example.unifiedreader.ui.components.AppToastIcon
import com.example.unifiedreader.ui.components.AppToastMessage
import com.example.unifiedreader.ui.components.LocalAppToastState

/**
 * What the Bookmarks screen's bar holds: on the books, the menu button and Export all (every
 * book, Markdown); on a book, Back and Export (that book); in the review, Back and its title.
 * The bar names no screen: the menu already says where you are.
 */
@Composable
fun BookmarksBar(
    state: BookmarksState,
    library: MarkedLibrary?,
    actions: BookmarksActions,
    menuButton: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val toasts = LocalAppToastState.current
    val save = rememberMarkdownSaver { saved ->
        toasts?.show(
            if (saved) {
                AppToastMessage(text = BookmarksCopy.Exported, icon = AppToastIcon.Done)
            } else {
                AppToastMessage(text = BookmarksCopy.ExportFailed, icon = AppToastIcon.Error)
            },
        )
    }
    // The bar changes with the page under it, in step with its slide.
    AppBarSwitch(targetState = state.page, modifier = modifier.fillMaxWidth(), label = "bookmarksBar") { page ->
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (page) {
            BookmarksPage.Library -> {
                menuButton?.invoke()
                Spacer(Modifier.weight(1f))
                if (library != null && !library.isEmpty) {
                    AppButton(onClick = { save(actions::exportAll) }) {
                        Icon(imageVector = AppIcons.Download, contentDescription = null, modifier = Modifier.size(SmallIcon))
                        Spacer(Modifier.width(AppLayout.headingGap))
                        Text(BookmarksCopy.ExportAll)
                    }
                }
            }
            is BookmarksPage.Book -> {
                BackButton(BookmarksCopy.Back) { state.back() }
                Spacer(Modifier.weight(1f))
                library?.book(page.itemId)?.let { book ->
                    AppTonalButton(onClick = { save { actions.exportBook(book.book) } }) {
                        Icon(imageVector = AppIcons.Download, contentDescription = null, modifier = Modifier.size(SmallIcon))
                        Spacer(Modifier.width(AppLayout.headingGap))
                        Text(BookmarksCopy.Export)
                    }
                }
            }
            is BookmarksPage.Review -> {
                BackButton(if (page.from is BookmarksPage.Book) BookmarksCopy.BackToBook else BookmarksCopy.Back) { state.back() }
                Text(
                    text = BookmarksCopy.ReviewTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    }
}

@Composable
private fun BackButton(description: String, onClick: () -> Unit) {
    AppIconButton(onClick = onClick) {
        Icon(imageVector = AppIcons.ArrowBack, contentDescription = description)
    }
}
