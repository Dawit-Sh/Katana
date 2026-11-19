package com.example.unifiedreader

import com.example.unifiedreader.feature.lists.SmartListDraft
import com.example.unifiedreader.feature.lists.UserList
import com.example.unifiedreader.feature.lists.UserListDraft
import com.example.unifiedreader.ui.components.AppScreenTransition

/**
 * Which page of Lists fills the screen: every list ([listId] null), one list, or an editor over
 * them ([editing]: a list's or a smart list's). One level deeper is one more of each.
 */
internal data class ListsPage(val listId: String?, val editing: Boolean) {
    val depth: Int
        get() = (if (listId != null) 1 else 0) + (if (editing) 1 else 0)
}

/** The page the Lists route shows for this open list and these drafts (the smart list editor first, as it draws). */
internal fun listsPage(openList: UserList?, draft: UserListDraft?, smartDraft: SmartListDraft?): ListsPage = ListsPage(
    listId = openList?.id,
    editing = smartDraft != null || (openList != null && draft?.listId == openList.id),
)

/** A list slides in over the lists and an editor over its list; going back slides the other way. */
internal fun listsPageTransition(from: ListsPage, to: ListsPage): AppScreenTransition = when {
    to.depth > from.depth -> AppScreenTransition.Forward
    to.depth < from.depth -> AppScreenTransition.Backward
    else -> AppScreenTransition.Fade
}

/** What one Lists page draws from, kept per page while it animates out. */
internal class ListsPageFrame(
    val openList: UserList?,
    val draft: UserListDraft?,
    val smartDraft: SmartListDraft?,
)
