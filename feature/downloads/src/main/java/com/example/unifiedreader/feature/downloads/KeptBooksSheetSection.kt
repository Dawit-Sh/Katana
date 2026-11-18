package com.example.unifiedreader.feature.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.core.model.formatStorageSize
import com.example.unifiedreader.core.model.libraryPrimaryText
import com.example.unifiedreader.feature.library.LibraryCoverImageVariant
import com.example.unifiedreader.feature.library.ResolvedLibraryCover
import com.example.unifiedreader.ui.components.AppBookRowDefaults
import com.example.unifiedreader.ui.components.AppDialog
import com.example.unifiedreader.ui.components.AppDialogConfirmButton
import com.example.unifiedreader.ui.components.AppDialogDismissButton
import com.example.unifiedreader.ui.components.AppIconButton
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppLayout
import com.example.unifiedreader.ui.components.AppProgressBar
import com.example.unifiedreader.ui.components.AppSheetDivider
import com.example.unifiedreader.ui.components.AppTonalButton

/** The books "On this tablet" shows, with what Remove does; none when the app gives no actions. */
internal class ShownKeptBooks(
    val snapshot: KeptBooksSnapshot,
    val actions: KeptBookActions?,
) {
    val books: List<KeptBook>
        get() = if (actions == null) emptyList() else snapshot.books
}

/** The line under the sheet's title: what downloads now, else what the tablet holds. */
internal fun downloadsHeaderSummary(snapshot: DownloadEntriesSnapshot, kept: ShownKeptBooks): String? {
    val active = snapshot.entries.any { entry -> entry.sheetSection() != DownloadsSheetSection.Finished }
    val keptSummary = keptBooksSummary(kept.snapshot).takeIf { kept.books.isNotEmpty() }
    return if (active) downloadsSheetSummary(snapshot) else keptSummary ?: downloadsSheetSummary(snapshot)
}

/**
 * "On this tablet": each book whose file is here, the latest read first, with Remove; then, when
 * the queue is empty ([withNothingDownloading]), how downloading starts. Nothing without books.
 */
internal fun LazyListScope.keptBooksSection(
    kept: ShownKeptBooks,
    now: Long,
    context: DownloadRowContext,
    withNothingDownloading: Boolean,
    onOpenBookPage: (KeptBook) -> Unit,
) {
    val actions = kept.actions ?: return
    val books = kept.books.ifEmpty { return }
    sheetHeading(key = "section-on-this-tablet", title = KeptBooksCopy.Heading)
    item(key = "on-this-tablet-note") {
        Text(
            text = KeptBooksCopy.Note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = AppLayout.inset).padding(bottom = AppLayout.headingGap),
        )
    }
    items(books, key = { book -> "kept-${book.item.id}" }) { book ->
        val title = book.item.libraryPrimaryText()
        SheetBookRow(
            title = title,
            line = book.sheetLine(now),
            onClick = { onOpenBookPage(book) },
            cover = { KeptBookCover(book = book, context = context) },
            modifier = Modifier.downloadRowMotion(this),
        ) {
            AppIconButton(onClick = { actions.onRemove(listOf(book.item.id)) }) {
                Icon(
                    imageVector = AppIcons.Delete,
                    contentDescription = KeptBooksCopy.removeOne(title),
                    modifier = Modifier.size(RemoveIconSize),
                )
            }
        }
    }
    if (withNothingDownloading) nothingDownloading()
}

/** Under "On this tablet" when nothing else is in the sheet: how downloading starts. */
private fun LazyListScope.nothingDownloading() {
    sheetHeading(key = "section-downloading-none", title = DownloadsSheetSection.Downloading.title)
    item(key = "downloading-none-note") {
        Text(
            text = KeptBooksCopy.NothingDownloading,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = AppLayout.inset),
        )
    }
}

@Composable
private fun KeptBookCover(book: KeptBook, context: DownloadRowContext) {
    val width = AppBookRowDefaults.coverWidth
    Box(modifier = Modifier.size(width = width, height = width * CoverAspect)) {
        LaunchedEffect(book.item.id) {
            context.onCoverVisible(book.item, LibraryCoverImageVariant.ShelfRow)
        }
        ResolvedLibraryCover(
            item = book.item,
            coverImageUrlProvider = context.coverImageUrlProvider,
            imageVariant = LibraryCoverImageVariant.ShelfRow,
            showReadBadge = false,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Under the list: the space the books take, then Remove finished books and Remove all, each asked first. */
@Composable
internal fun KeptBooksFooter(kept: ShownKeptBooks) {
    val actions = kept.actions ?: return
    if (kept.books.isEmpty()) return
    val snapshot = kept.snapshot
    var confirming by remember { mutableStateOf<KeptBooksRemoval?>(null) }
    val finished = snapshot.finishedBooks
    AppSheetDivider()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppLayout.inset, vertical = AppLayout.menuInset),
        verticalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = KeptBooksCopy.Total,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatStorageSize(snapshot.totalBytes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AppProgressBar(fraction = keptBooksShare(snapshot), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(AppLayout.headingGap)) {
            AppTonalButton(
                onClick = { confirming = KeptBooksRemoval.Finished },
                enabled = finished.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text(KeptBooksCopy.RemoveFinished, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            AppTonalButton(
                onClick = { confirming = KeptBooksRemoval.All },
                modifier = Modifier.weight(1f),
            ) { Text(KeptBooksCopy.RemoveAll, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
    confirming?.let { removal ->
        val books = if (removal == KeptBooksRemoval.Finished) finished else snapshot.books
        AppDialog(
            onDismissRequest = { confirming = null },
            title = { Text(KeptBooksCopy.confirmTitle(removal, books.size)) },
            text = { Text(KeptBooksCopy.ConfirmBody) },
            confirmButton = {
                AppDialogConfirmButton(
                    text = "Remove",
                    destructive = true,
                    onClick = {
                        confirming = null
                        actions.onRemove(books.map { book -> book.item.id })
                    },
                )
            },
            dismissButton = { AppDialogDismissButton(text = "Cancel", onClick = { confirming = null }) },
        )
    }
}

internal enum class KeptBooksRemoval { Finished, All }

internal object KeptBooksCopy {
    const val Heading = "On this tablet"
    const val Note = "Books you opened are kept so they read offline, the same as a download."
    const val NothingDownloading = "Nothing waiting. Long-press covers in the library to download several at once."
    const val Total = "Books on this tablet"
    const val RemoveFinished = "Remove finished"
    const val RemoveAll = "Remove all"
    const val ConfirmBody = "Their files leave this tablet. Your place, highlights and notes stay, and the " +
        "books stay in your library to open again from the server."

    fun removeOne(title: String): String = "Remove $title from this tablet"

    fun confirmTitle(removal: KeptBooksRemoval, count: Int): String {
        val books = if (count == 1) "1 book" else "$count books"
        return when (removal) {
            KeptBooksRemoval.Finished -> "Remove $books you've finished?"
            KeptBooksRemoval.All -> "Remove all $books from this tablet?"
        }
    }
}

private const val CoverAspect = 1.5f
private val RemoveIconSize = 20.dp
