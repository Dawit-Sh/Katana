package com.example.unifiedreader.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.example.unifiedreader.core.model.ReadStatus
import com.example.unifiedreader.ui.components.AppChip
import com.example.unifiedreader.ui.components.AppChipDefaults
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppMenu
import com.example.unifiedreader.ui.components.AppMenuChoiceItem
import com.example.unifiedreader.ui.components.AppMenuHeading
import com.example.unifiedreader.ui.theme.AppCoverColors

/**
 * The book page's book's reading status, from the app ([LocalLibraryBookPageStatus]): [own] is the status it
 * was given (here, or on its server), null for none, when the page shows what its place says; [choices] are
 * the ones it takes; [onSelect] sets one of them other than Read and Unread, which are Mark finished and Mark
 * unread (the page runs those through its action context, as before).
 */
@Immutable
data class LibraryBookStatus(
    val own: ReadStatus?,
    val choices: List<ReadStatus>,
    val onSelect: (ReadStatus) -> Unit,
)

/** The reading status of the book the page shows, read while it shows; none leaves the pill out. */
val LocalLibraryBookPageStatus = staticCompositionLocalOf<LibraryBookStatus?> { null }

/** The status the pill shows: the book's own, or for a book never given one, what its progress says. */
internal fun libraryBookShownStatus(own: ReadStatus?, progress: ItemProgressSummary?): ReadStatus = own ?: when {
    progress?.isComplete == true -> ReadStatus.READ
    progress?.hasStarted == true -> ReadStatus.READING
    else -> ReadStatus.UNREAD
}

/**
 * The reading status under the book's progress: a pill in the cover's colours ("Re-reading ▾") whose menu lists
 * the statuses the book takes, the shown one checked. Picking the one shown does nothing.
 */
@Composable
internal fun LibraryBookStatusPill(
    shown: ReadStatus,
    choices: List<ReadStatus>,
    tint: AppCoverColors,
    onSelect: (ReadStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    var choosing by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        AppChip(
            selected = false,
            onClick = { choosing = true },
            label = { Text(text = shown.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = {
                Icon(
                    imageVector = AppIcons.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(AppChipDefaults.iconSize),
                )
            },
            colors = AppChipDefaults.colors(contentColor = tint.content),
            border = AppChipDefaults.border(enabled = true, selected = false, color = tint.track),
            modifier = Modifier.semantics { contentDescription = "${LibraryBookStatusCopy.Heading}: ${shown.label}" },
        )
        AppMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
            AppMenuHeading(text = LibraryBookStatusCopy.Heading)
            choices.forEach { status ->
                AppMenuChoiceItem(
                    text = status.label,
                    selected = status == shown,
                    onClick = {
                        choosing = false
                        if (status != shown) onSelect(status)
                    },
                )
            }
        }
    }
}

internal object LibraryBookStatusCopy {
    const val Heading = "Reading status"
}
