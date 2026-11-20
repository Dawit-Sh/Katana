package com.example.unifiedreader

import com.example.unifiedreader.core.database.dao.LocalServerLinkDao
import com.example.unifiedreader.core.database.entity.LOCAL_LINK_CHANGED
import com.example.unifiedreader.core.database.entity.LOCAL_LINK_LINKED

/**
 * After a book from a file came back under a new id and its marks moved from [fromItemId] to [toItemId]: its
 * Grimmory link goes along (the next check confirms it against the file, or marks it changed), unless the new id
 * has one of its own. The old row stays, marked changed when it was linked: link rows are never deleted.
 */
internal fun LocalServerLinkDao.carryLink(fromItemId: String, toItemId: String, nowEpochMillis: Long) {
    if (fromItemId == toItemId) return
    val old = getLink(fromItemId) ?: return
    if (getLink(toItemId) == null) upsertLink(old.copy(localItemId = toItemId))
    if (old.state == LOCAL_LINK_LINKED) {
        upsertLink(old.copy(state = LOCAL_LINK_CHANGED, checkedAtEpochMillis = nowEpochMillis))
    }
}
