package com.example.unifiedreader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.feature.library.LibraryBrowseTopBarSearchField
import com.example.unifiedreader.feature.lists.ListsRouteState
import com.example.unifiedreader.feature.lists.SmartListDraft
import com.example.unifiedreader.feature.lists.UserListDraft
import com.example.unifiedreader.feature.lists.UserListKind
import com.example.unifiedreader.feature.lists.books
import com.example.unifiedreader.ui.components.AppBarSwitch
import com.example.unifiedreader.ui.components.AppButton
import com.example.unifiedreader.ui.components.AppIconButton
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppLayout
import com.example.unifiedreader.ui.components.AppListRowDefaults
import com.example.unifiedreader.ui.components.AppMenu
import com.example.unifiedreader.ui.components.AppMenuItem
import com.example.unifiedreader.ui.components.AppTextButton
import com.example.unifiedreader.ui.components.AppToastIcon
import com.example.unifiedreader.ui.components.AppToastMessage
import com.example.unifiedreader.ui.components.AppTonalButton
import com.example.unifiedreader.ui.components.LocalAppToastState

/**
 * The Lists bar: the menu, "Search lists", New (List, Smart list, Import a reading order) and ⋯
 * (What Lists shows, Select several) on the lists; Close, how many are picked, Select all, Pin
 * and Hide while several are selected; Back on a list; Cancel, "Edit list" and Done while one is
 * edited, and Close, "New smart list" and Create in the smart list editor. No glass until
 * something scrolls under it.
 */
@Composable
internal fun ListsTopBar(
    state: ListsRouteState,
    listsViewModel: AppListsViewModel,
    onDrawerClick: (() -> Unit)?,
    // The library's books, for Undo after Remove.
    catalog: List<ReadableItem> = emptyList(),
) {
    val onShelfFailure = rememberShelfFailureToast()
    ThemedTopBarContainer(surfaceVisible = state.scrolled) {
        // The bar changes with the page under it, in step with its slide; a bar that is leaving
        // keeps what it showed.
        val bar = ListsBarFrame(
            selection = state.selection,
            smartDraft = state.smartDraft,
            draft = state.editDraft,
            openListId = state.openListId,
            selectingBooks = state.bookSelection != null,
        )
        AppBarSwitch(targetState = bar, contentKey = ListsBarFrame::kind, contentAlignment = Alignment.CenterStart, label = "listsBar") { shown ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val draft = shown.draft
            val smartDraft = shown.smartDraft
            val selection = shown.selection
            when {
                selection != null && shown.openListId == null -> ListsSelectionBar(state, selection, listsViewModel)
                smartDraft != null -> {
                    AppIconButton(onClick = { state.smartDraft = null }) {
                        Icon(imageVector = AppIcons.Close, contentDescription = if (smartDraft.isNew) "Cancel the new smart list" else "Cancel editing")
                    }
                    Text(
                        text = when {
                            smartDraft.isNew -> "New smart list"
                            smartDraft.onServer != null -> "Edit magic shelf"
                            else -> "Edit smart list"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    AppButton(
                        onClick = {
                            state.smartDraft = null
                            listsViewModel.saveSmartDraft(smartDraft, onCreated = state::openList, onFailure = onShelfFailure)
                        },
                        enabled = smartDraft.canSave,
                    ) {
                        Text(text = if (smartDraft.isNew) "Create" else "Done", fontWeight = FontWeight.SemiBold)
                    }
                }
                draft != null -> {
                    AppIconButton(onClick = { state.editDraft = null }) {
                        Icon(imageVector = AppIcons.Close, contentDescription = "Cancel editing")
                    }
                    Text(
                        text = when (draft.kind) {
                            UserListKind.ServerShelf -> "Edit shelf"
                            UserListKind.ServerCollection -> "Edit collection"
                            UserListKind.ServerReadingList -> "Edit reading list"
                            else -> "Edit list"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    AppButton(
                        onClick = {
                            listsViewModel.applyDraft(draft, onFailure = onShelfFailure)
                            state.editDraft = null
                        },
                        enabled = draft.name.isNotBlank(),
                    ) {
                        Text(text = "Done", fontWeight = FontWeight.SemiBold)
                    }
                }
                shown.openListId != null && shown.selectingBooks -> ListBooksSelectionBar(state, listsViewModel, catalog, onShelfFailure)
                shown.openListId != null -> {
                    AppIconButton(onClick = { state.back() }) {
                        Icon(imageVector = AppIcons.ArrowBack, contentDescription = "Back to lists")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    ListPageMoreButton(shown.openListId, state, listsViewModel)
                }
                else -> {
                    if (onDrawerClick != null) DrawerMenuButton(onClick = onDrawerClick)
                    LibraryBrowseTopBarSearchField(
                        query = state.searchQuery,
                        onQueryChanged = { query -> state.searchQuery = query },
                        placeholder = "Search lists",
                        queryContentDescription = "Search lists",
                        modifier = Modifier.weight(1f),
                    )
                    NewListMenuButton(
                        onList = { state.isNewListOpen = true },
                        onSmartList = { state.isSmartSourcePickOpen = true },
                        onImport = { state.isImportRequested = true },
                    )
                    ListsMoreMenuButton(
                        onWhatListsShows = { state.isWhatListsShowsOpen = true },
                        onSelect = { state.selection = emptySet() },
                    )
                }
            }
        }
        }
    }
}

/** What the Lists bar draws from; bars of the same [kind] are one bar that changes in place. */
private class ListsBarFrame(
    val selection: Set<String>?,
    val smartDraft: SmartListDraft?,
    val draft: UserListDraft?,
    val openListId: String?,
    val selectingBooks: Boolean,
) {
    val kind: Any
        get() = when {
            selection != null && openListId == null -> "selecting lists"
            smartDraft != null -> "smart list editor"
            draft != null -> "list editor"
            openListId != null && selectingBooks -> "selecting books"
            openListId != null -> openListId
            else -> "lists"
        }
}

/** New: a menu of what to make, each saying what it is. */
@Composable
private fun NewListMenuButton(onList: () -> Unit, onSmartList: () -> Unit, onImport: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AppIconButton(onClick = { open = true }) {
            Icon(imageVector = AppIcons.Add, contentDescription = "New")
        }
        AppMenu(expanded = open, onDismissRequest = { open = false }) {
            AppMenuItem(
                text = { Text("Smart list") },
                supporting = "Filters in a library, kept up to date",
                leadingIcon = { Icon(imageVector = AppIcons.Tune, contentDescription = null) },
                onClick = { open = false; onSmartList() },
            )
            AppMenuItem(
                text = { Text("List") },
                supporting = "You pick the books, in your order",
                leadingIcon = { Icon(imageVector = AppIcons.Checklist, contentDescription = null) },
                onClick = { open = false; onList() },
            )
            AppMenuItem(
                text = { Text("Import a reading order") },
                supporting = "From a .cbl file",
                leadingIcon = { Icon(imageVector = AppIcons.Description, contentDescription = null) },
                onClick = { open = false; onImport() },
            )
        }
    }
}

/**
 * ⋯ on a list's page: Change filters (a smart list), Add books and Select books (a list you pick),
 * Rename, Pin to the top (or Unpin), Hide from Lists (or Show again), then Delete list.
 */
@Composable
private fun ListPageMoreButton(listId: String, state: ListsRouteState, listsViewModel: AppListsViewModel) {
    val preferences by listsViewModel.preferences.collectAsStateWithLifecycle()
    val preference = preferences[listId]
    val list = state.openListSnapshot?.takeIf { it.id == listId }
    var open by remember { mutableStateOf(false) }
    Box {
        AppIconButton(onClick = { open = true }) {
            Icon(imageVector = AppIcons.MoreVert, contentDescription = "More for this list")
        }
        AppMenu(expanded = open, onDismissRequest = { open = false }) {
            if (list != null && list.isEditable && list.smart != null) {
                AppMenuItem(
                    text = { Text("Change filters") },
                    supporting = if (list.changesInFilters) "Opens Filters with this list's picks" else "Its rules",
                    leadingIcon = { Icon(imageVector = AppIcons.Tune, contentDescription = null) },
                    onClick = { open = false; state.isChangeFiltersRequested = true },
                )
            }
            if (list != null && list.takesBooksByHand) {
                AppMenuItem(
                    text = { Text("Add books") },
                    supporting = "Pick them in the library",
                    leadingIcon = { Icon(imageVector = AppIcons.Add, contentDescription = null) },
                    onClick = { open = false; state.isAddBooksRequested = true },
                )
                AppMenuItem(
                    text = { Text("Select books") },
                    supporting = "To remove them or move them up",
                    leadingIcon = { Icon(imageVector = AppIcons.Checklist, contentDescription = null) },
                    onClick = { open = false; state.bookSelection = emptySet() },
                )
            }
            if (list != null && list.isEditable && list.kind != UserListKind.WantToRead) {
                AppMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(imageVector = AppIcons.Edit, contentDescription = null) },
                    onClick = { open = false; state.isRenameOpen = true },
                )
            }
            val pinned = preference?.isPinned == true
            AppMenuItem(
                text = { Text(if (pinned) "Unpin" else "Pin to the top") },
                leadingIcon = { Icon(imageVector = if (pinned) AppIcons.KeepOff else AppIcons.Keep, contentDescription = null) },
                onClick = { open = false; listsViewModel.setPinned(listOf(listId), pinned = !pinned) },
            )
            val hidden = preference?.hidden == true
            AppMenuItem(
                text = { Text(if (hidden) "Show again" else "Hide from Lists") },
                leadingIcon = { Icon(imageVector = AppIcons.VisibilityOff, contentDescription = null) },
                onClick = { open = false; listsViewModel.setHidden(listOf(listId), hidden = !hidden) },
            )
            if (list != null && list.isEditable && (list.kind == UserListKind.User || list.kind == UserListKind.Smart || list.server != null)) {
                AppMenuItem(
                    text = { Text("Delete list", color = MaterialTheme.colorScheme.error) },
                    supporting = "Its books stay in your library",
                    leadingIcon = { Icon(imageVector = AppIcons.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { open = false; state.isDeleteOpen = true },
                )
            }
        }
    }
}

/**
 * The bar while books are picked on a list's page: Close, how many, Move to the top (a list with
 * an order of its own) and Remove, each saved at once.
 */
@Composable
private fun RowScope.ListBooksSelectionBar(
    state: ListsRouteState,
    listsViewModel: AppListsViewModel,
    catalog: List<ReadableItem>,
    onFailure: (String) -> Unit,
) {
    val picked = state.bookSelection.orEmpty()
    val list = state.openListSnapshot
    val toasts = LocalAppToastState.current
    // Each change is saved at once; its toast takes it back.
    fun change(remove: Boolean) {
        if (list == null) return
        val undo = changeListBooks(list, picked, remove = remove, listsViewModel = listsViewModel, catalog = catalog, onFailure = onFailure)
        state.bookSelection = null
        val count = if (picked.size == 1) "1 book" else "${picked.size} books"
        toasts?.show(
            AppToastMessage(
                text = if (remove) "$count removed" else "$count moved to the top",
                icon = if (remove) AppToastIcon.Removed else AppToastIcon.Done,
                actionLabel = "Undo",
                onAction = undo,
            ),
        )
    }
    AppIconButton(onClick = { state.bookSelection = null }) {
        Icon(imageVector = AppIcons.Close, contentDescription = "Stop selecting")
    }
    Text(
        text = "${picked.size} selected",
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
    )
    if (list != null && picked.size < list.entries.size) {
        AppTextButton(onClick = { state.bookSelection = list.entries.mapTo(LinkedHashSet()) { entry -> entry.itemId } }) {
            Text(text = "Select all", fontWeight = FontWeight.SemiBold)
        }
    }
    if (list != null && list.kind != UserListKind.ServerShelf && list.kind != UserListKind.ServerCollection) {
        AppTextButton(onClick = { change(remove = false) }, enabled = picked.isNotEmpty()) {
            Text(text = "Move to the top", fontWeight = FontWeight.SemiBold)
        }
    }
    AppButton(onClick = { change(remove = true) }, enabled = list != null && picked.isNotEmpty()) {
        Text(text = "Remove", fontWeight = FontWeight.SemiBold)
    }
}

/** ⋯ on the lists: What Lists shows, and Select several. */
@Composable
private fun ListsMoreMenuButton(onWhatListsShows: () -> Unit, onSelect: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AppIconButton(onClick = { open = true }) {
            Icon(imageVector = AppIcons.MoreVert, contentDescription = "More")
        }
        AppMenu(expanded = open, onDismissRequest = { open = false }) {
            AppMenuItem(
                text = { Text("What Lists shows") },
                leadingIcon = { Icon(imageVector = AppIcons.VisibilityOff, contentDescription = null) },
                onClick = { open = false; onWhatListsShows() },
            )
            AppMenuItem(
                text = { Text("Select several") },
                leadingIcon = { Icon(imageVector = AppIcons.SelectAll, contentDescription = null) },
                onClick = { open = false; onSelect() },
            )
        }
    }
}

/**
 * The bar while several lists are picked: Close, how many, Select all, then Pin and Hide, which
 * end the selection and say what they did in a toast with Undo.
 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.ListsSelectionBar(
    state: ListsRouteState,
    selection: Set<String>,
    listsViewModel: AppListsViewModel,
) {
    val toasts = LocalAppToastState.current
    AppIconButton(onClick = { state.selection = null }) {
        Icon(imageVector = AppIcons.Close, contentDescription = "Stop selecting")
    }
    Text(
        text = if (selection.isEmpty()) "Select lists" else "${selection.size} selected",
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
    )
    AppIconButton(onClick = { state.selection = state.selectableIds.toSet() }) {
        Icon(imageVector = AppIcons.SelectAll, contentDescription = "Select all")
    }
    val ids = selection.toList()
    AppTonalButton(
        onClick = {
            // Only the ones not pinned yet, so Undo leaves the others pinned.
            val preferences = listsViewModel.preferences.value
            val pinning = ids.filter { id -> preferences[id]?.isPinned != true }
            state.selection = null
            listsViewModel.setPinned(pinning, pinned = true)
            toasts?.show(
                AppToastMessage(
                    text = if (pinning.size == 1) "1 list pinned" else "${pinning.size} lists pinned",
                    actionLabel = "Undo",
                    onAction = { listsViewModel.setPinned(pinning, pinned = false) },
                ),
            )
        },
        enabled = ids.isNotEmpty(),
    ) {
        Icon(imageVector = AppIcons.Keep, contentDescription = null, modifier = Modifier.size(AppListRowDefaults.markSize))
        Spacer(modifier = Modifier.width(AppLayout.headingGap))
        Text(text = "Pin", fontWeight = FontWeight.SemiBold)
    }
    AppButton(
        onClick = {
            state.selection = null
            listsViewModel.setHidden(ids, hidden = true)
            toasts?.show(
                AppToastMessage(
                    text = if (ids.size == 1) "1 list hidden" else "${ids.size} lists hidden",
                    actionLabel = "Undo",
                    onAction = { listsViewModel.setHidden(ids, hidden = false) },
                ),
            )
        },
        enabled = ids.isNotEmpty(),
    ) {
        Icon(imageVector = AppIcons.VisibilityOff, contentDescription = null, modifier = Modifier.size(AppListRowDefaults.markSize))
        Spacer(modifier = Modifier.width(AppLayout.headingGap))
        Text(text = "Hide", fontWeight = FontWeight.SemiBold)
    }
}
