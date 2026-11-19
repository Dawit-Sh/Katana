package com.example.unifiedreader.feature.annotations

import com.example.unifiedreader.core.database.entity.HIGHLIGHT_IN_STEP
import com.example.unifiedreader.core.database.entity.HIGHLIGHT_WAITING
import com.example.unifiedreader.core.database.entity.HighlightRowPurge
import com.example.unifiedreader.core.database.entity.HighlightRowWrite
import com.example.unifiedreader.core.database.entity.ReaderHighlightEntity

/**
 * A highlight as the book's server has it. [cfi] is its place (an EPUB CFI range), null when the
 * list it came in leaves places out (Grimmory's list of every book's highlights). [updatedAtEpochMillis]
 * is the server's time of its last change.
 */
data class RemoteHighlight(
    val remoteId: String,
    val cfi: String?,
    val text: String,
    val note: String?,
    val color: HighlightColor,
    val style: HighlightStyle,
    val chapterTitle: String?,
    val createdAtEpochMillis: Long?,
    val updatedAtEpochMillis: Long?,
)

/** A change for the server, made from a highlight on this device as it was when the sync read it ([expectedUpdatedAt]). */
sealed interface HighlightUpload {
    val localId: String
    val expectedUpdatedAt: Long

    data class Create(
        override val localId: String,
        override val expectedUpdatedAt: Long,
        val cfi: String,
        val text: String,
        val note: String?,
        val color: HighlightColor,
        val style: HighlightStyle,
        val chapterTitle: String?,
    ) : HighlightUpload

    data class Update(
        override val localId: String,
        override val expectedUpdatedAt: Long,
        val remoteId: String,
        val note: String?,
        val color: HighlightColor,
        val style: HighlightStyle,
    ) : HighlightUpload

    data class Delete(
        override val localId: String,
        override val expectedUpdatedAt: Long,
        val remoteId: String,
    ) : HighlightUpload
}

/** What a sync of one book does: [writes] and [purges] on this device, then [uploads] to the server. */
data class HighlightSyncPlan(
    val writes: List<HighlightRowWrite> = emptyList(),
    val purges: List<HighlightRowPurge> = emptyList(),
    val uploads: List<HighlightUpload> = emptyList(),
    /**
     * The server's list came without places and has a highlight that may be one this device has under
     * another or no id yet (one it made and hasn't heard back about, or one KOReader made again when it
     * was changed there): only the book's own list, with places, can tell. Nothing else in the plan.
     */
    val needsPlaces: Boolean = false,
)

/**
 * How much newer the server's copy of a highlight has to be than a change on this device not yet
 * sent, to win over it: the same margin as reading progress, since the server stamps a change with
 * its own clock when it lands, which may run ahead of this device's.
 */
const val HIGHLIGHT_CLOCK_MARGIN_MILLIS: Long = 2 * 60 * 1000L

/**
 * Brings one book's highlights on this device ([local], removed ones included) and on the server
 * ([remote], the book's complete list there) in step. The rules:
 * - A highlight is the same on both sides by the server's id; one this device made that has no id
 *   yet (or one whose id the server dropped) is matched by its place or its words in the chapter, so
 *   a lost answer, KOReader making a changed highlight again, or the same words highlighted in
 *   KOReader and here don't leave two.
 * - The last change wins. A change on this device not yet sent loses only to a server copy newer by
 *   [HIGHLIGHT_CLOCK_MARGIN_MILLIS]; a highlight in step simply takes the server's copy.
 * - A highlight removed on one side is removed on the other, unless the other side changed it later
 *   (a change on this device not yet sent puts it back on the server).
 * - A new highlight on this device goes up once its place on the server is worked out (by the reader).
 */
fun planHighlightSync(
    book: AnnotatedBook,
    local: List<ReaderHighlightEntity>,
    remote: List<RemoteHighlight>,
    nowEpochMillis: Long,
    newId: () -> String,
): HighlightSyncPlan {
    val remoteIds = remote.mapTo(mutableSetOf(), RemoteHighlight::remoteId)
    val byRemoteId = local.filter { row -> row.remoteId != null }.associateBy { row -> row.remoteId }
    // Highlights the server may have under an id this device doesn't know.
    val unmatchedOnServer = local.filter { row -> row.remoteId == null || row.remoteId !in remoteIds }
    val placesKnown = remote.all { highlight -> highlight.cfi != null }
    if (!placesKnown && remote.any { it.remoteId !in byRemoteId } && unmatchedOnServer.any(::mayBeOnServerUnderAnotherId)) {
        return HighlightSyncPlan(needsPlaces = true)
    }

    val plan = PlanBuilder()
    val matchedLocalIds = mutableSetOf<String>()
    remote.forEach { server ->
        val row = byRemoteId[server.remoteId]
            ?: unmatchedOnServer.firstOrNull { row -> row.id !in matchedLocalIds && row.isSameHighlightAs(server) }
        if (row == null) {
            // The same words made here and not yet placed the server's way: wait until they are, to match them.
            if (local.none { it.isUnplacedNew() && wordsKey(it.text) == wordsKey(server.text) }) {
                plan.writes += HighlightRowWrite(server.toNewRow(book, newId(), nowEpochMillis), expectedUpdatedAt = null)
            }
        } else {
            matchedLocalIds += row.id
            plan.matched(row, server)
        }
    }
    local.filterNot { row -> row.id in matchedLocalIds }.forEach(plan::notOnServer)
    return plan.build()
}

/**
 * A highlight here that is [server]'s under another id (or none yet): at the same place, however
 * the CFI is written, or the same words in the same chapter (two readers may count a place's
 * characters a little differently). One removed here before it ever reached the server is no one's.
 */
private fun ReaderHighlightEntity.isSameHighlightAs(server: RemoteHighlight): Boolean {
    val place = remoteCfi?.takeUnless { deleted && remoteId == null }
    val serverPlace = server.cfi
    return place != null && serverPlace != null && (
        sameCfiPlace(place, serverPlace) ||
            (cfiChapterKey(place) == cfiChapterKey(serverPlace) && wordsKey(text) == wordsKey(server.text))
        )
}

private fun ReaderHighlightEntity.isUnplacedNew(): Boolean =
    !deleted && remoteId == null && remoteCfi == null && syncState == HIGHLIGHT_WAITING

// A highlight this device has that the server may hold under an id this device doesn't know: one
// whose id the server dropped, or one sent without hearing back (only a placed one is ever sent).
private fun mayBeOnServerUnderAnotherId(row: ReaderHighlightEntity): Boolean =
    !row.deleted && (row.remoteId != null || (row.syncState == HIGHLIGHT_WAITING && row.remoteCfi != null))

private class PlanBuilder {
    val writes = mutableListOf<HighlightRowWrite>()
    val purges = mutableListOf<HighlightRowPurge>()
    val uploads = mutableListOf<HighlightUpload>()

    fun build() = HighlightSyncPlan(writes = writes, purges = purges, uploads = uploads)

    fun matched(row: ReaderHighlightEntity, server: RemoteHighlight) {
        val linked = row.copy(remoteId = server.remoteId, remoteCfi = row.remoteCfi ?: server.cfi)
        val serverNewer = server.updatedAtEpochMillis?.let { it > row.updatedAtEpochMillis + HIGHLIGHT_CLOCK_MARGIN_MILLIS } ?: false
        when {
            row.syncState == HIGHLIGHT_IN_STEP && !row.deleted ->
                write(row, if (row.differsFrom(server)) linked.takingServerCopy(server) else linked)
            serverNewer -> write(row, linked.takingServerCopy(server).copy(deleted = false))
            row.deleted -> {
                write(row, linked)
                uploads += HighlightUpload.Delete(row.id, row.updatedAtEpochMillis, server.remoteId)
            }
            row.differsFrom(server) -> {
                write(row, linked)
                uploads += row.toUpdate(server.remoteId)
            }
            // The same on both sides already: nothing to send.
            else -> write(row, linked.copy(syncState = HIGHLIGHT_IN_STEP))
        }
    }

    fun notOnServer(row: ReaderHighlightEntity) {
        when {
            // Removed on the server, or on both sides; or removed here before it ever got there.
            row.deleted || (row.remoteId != null && row.syncState == HIGHLIGHT_IN_STEP) ->
                purges += HighlightRowPurge(row.id, row.updatedAtEpochMillis)
            row.syncState != HIGHLIGHT_WAITING -> Unit
            else -> {
                // Changed here after it was removed there, or never sent: it goes (back) up.
                if (row.remoteId != null) write(row, row.copy(remoteId = null))
                row.toCreate()?.let(uploads::add)
            }
        }
    }

    // Nothing when [row] is [stored] as it is.
    private fun write(stored: ReaderHighlightEntity, row: ReaderHighlightEntity) {
        if (row != stored) writes += HighlightRowWrite(row, expectedUpdatedAt = stored.updatedAtEpochMillis)
    }

    private fun ReaderHighlightEntity.takingServerCopy(server: RemoteHighlight) = copy(
        note = server.note,
        color = server.color.name,
        style = server.style.name,
        updatedAtEpochMillis = maxOf(updatedAtEpochMillis, server.updatedAtEpochMillis ?: updatedAtEpochMillis),
        syncState = HIGHLIGHT_IN_STEP,
    )
}

private fun ReaderHighlightEntity.differsFrom(server: RemoteHighlight): Boolean =
    note.asNote() != server.note.asNote() ||
        HighlightColor.fromStored(color) != server.color ||
        HighlightStyle.fromStored(style) != server.style

private fun String?.asNote(): String? = this?.trim()?.takeIf(String::isNotEmpty)

private fun ReaderHighlightEntity.toUpdate(remoteId: String) = HighlightUpload.Update(
    localId = id,
    expectedUpdatedAt = updatedAtEpochMillis,
    remoteId = remoteId,
    note = note,
    color = HighlightColor.fromStored(color),
    style = HighlightStyle.fromStored(style),
)

private fun ReaderHighlightEntity.toCreate(): HighlightUpload.Create? {
    val cfi = remoteCfi ?: return null
    return HighlightUpload.Create(
        localId = id,
        expectedUpdatedAt = updatedAtEpochMillis,
        cfi = cfi,
        text = text,
        note = note,
        color = HighlightColor.fromStored(color),
        style = HighlightStyle.fromStored(style),
        chapterTitle = chapterTitle,
    )
}

// A highlight new to this device: no place in its copy of the book yet (the reader finds it there).
private fun RemoteHighlight.toNewRow(book: AnnotatedBook, id: String, nowEpochMillis: Long): ReaderHighlightEntity {
    val updatedAt = updatedAtEpochMillis ?: createdAtEpochMillis ?: nowEpochMillis
    return ReaderHighlightEntity(
        id = id,
        itemId = book.itemId,
        bookTitle = book.title,
        bookAuthor = book.author,
        href = "",
        locatorJson = "",
        text = text.trim(),
        chapterTitle = chapterTitle,
        progression = null,
        style = style.name,
        color = color.name,
        note = note.asNote(),
        createdAtEpochMillis = createdAtEpochMillis ?: updatedAt,
        updatedAtEpochMillis = updatedAt,
        deleted = false,
        syncState = HIGHLIGHT_IN_STEP,
        remoteId = remoteId,
        remoteCfi = cfi,
    )
}
