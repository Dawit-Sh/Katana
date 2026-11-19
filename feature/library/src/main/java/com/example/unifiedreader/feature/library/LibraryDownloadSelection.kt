package com.example.unifiedreader.feature.library

import com.example.unifiedreader.core.model.ReadableItem

// A paged grid renders only its loaded window (about 180 roots), while a pick stays a pick after its
// page is dropped (a rail jump, a deep scroll). An in-memory grid renders the whole view, so there a
// pick it no longer renders is gone.

/** The picks kept when the grid's selectable roots change: a rendered root that isn't selectable drops out. */
internal fun sanitizeLibraryDownloadSelection(
    selectedIds: List<String>,
    renderedIds: Set<String>,
    selectableIds: Set<String>,
    isPaged: Boolean,
): List<String> = selectedIds.filter { itemId ->
    itemId in selectableIds || (isPaged && itemId !in renderedIds)
}

/** The picks in grid order: rendered roots first, then, for a paged grid, the picks outside the window. */
internal fun orderLibraryDownloadSelection(
    selectedIds: Set<String>,
    renderedIds: List<String>,
    isPaged: Boolean,
): List<String> {
    val rendered = renderedIds.filter { itemId -> itemId in selectedIds }
    if (!isPaged) return rendered
    val renderedIdSet = renderedIds.toSet()
    return rendered + selectedIds.filter { itemId -> itemId !in renderedIdSet }
}

/**
 * Whether the selection ends by itself because nothing in view can be picked. Never while picking books
 * for a list ([pickingAnyItem]): there every book can be picked, so a view with none (the covers not in
 * yet, a search that finds nothing) doesn't end it; Close and Back end it and go back to the list.
 */
fun librarySelectionRunsOut(
    selectableCount: Int,
    isSelectionMode: Boolean,
    pickingAnyItem: Boolean,
): Boolean = isSelectionMode && selectableCount == 0 && !pickingAnyItem

/**
 * The roots "Select all" and "Download selected" work over. The screen reports its rendered selectable
 * roots, which for a paged grid is only the loaded window; [viewDownloadableItems] (the recompute's
 * visible items, same membership as the paged view once it has landed) fills in the rest. Before the
 * screen reports, only [viewDownloadableItems] is known.
 */
fun libraryDownloadSelectableItems(
    screenSelectableItems: List<ReadableItem>?,
    viewDownloadableItems: List<ReadableItem>,
    isPaged: Boolean,
): List<ReadableItem> = when {
    screenSelectableItems == null -> viewDownloadableItems
    isPaged -> (screenSelectableItems + viewDownloadableItems).distinctBy(ReadableItem::id)
    else -> screenSelectableItems
}
