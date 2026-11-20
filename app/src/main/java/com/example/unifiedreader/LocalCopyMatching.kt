package com.example.unifiedreader

import com.example.unifiedreader.core.database.entity.LOCAL_LINK_CHANGED
import com.example.unifiedreader.core.database.entity.LOCAL_LINK_LINKED
import com.example.unifiedreader.core.database.entity.LOCAL_LINK_NOT_FOUND
import com.example.unifiedreader.core.database.entity.LocalServerLinkEntity
import com.example.unifiedreader.core.database.entity.RemoteFileCandidate
import com.example.unifiedreader.core.model.PartialMd5
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SourceType

/** A local file's exact size and KOReader partial MD5 ([PartialMd5]), the same fingerprint Grimmory keeps. */
data class LocalFileFingerprint(val sizeBytes: Long, val partialMd5: String)

/** A server file's exact size and the fingerprint of the samples read from it. */
data class RemoteFileFingerprint(val sizeBytes: Long, val partialMd5: String)

/** The links between local books and server books, as matching reads and writes them. */
interface LocalCopyLinkStore {
    suspend fun link(localItemId: String): LocalServerLinkEntity?

    suspend fun save(link: LocalServerLinkEntity)

    /** The server's books of [item]'s format whose file is [sizeKb] KB. */
    suspend fun candidates(item: ReadableItem, sizeKb: Long): List<RemoteFileCandidate>

    /** The server's book [remoteBookId] as the library has it now; null when it's no longer there. */
    suspend fun catalogBook(remoteBookId: String): RemoteFileCandidate?
}

/** What a check of a local book came to. */
sealed interface LocalCopyCheck {
    /** The very same file as server book [bookId]: its marks sync with it. */
    data class Linked(val bookId: String) : LocalCopyCheck

    /** No book there is this file (or it was, and one of the two files changed): its marks stay here. */
    data object NotLinked : LocalCopyCheck

    /** Can't tell now (the file unreadable, the server unreachable): nothing changes, nothing syncs. */
    data object Unknown : LocalCopyCheck
}

/**
 * Tells whether a local book is the very same file as one of a server's books, so its highlights and
 * bookmarks can sync with that book, and never links two different books:
 * - A match is the same format, the exact same size in bytes and the same partial MD5 (Grimmory keeps
 *   the same fingerprint). The server's file is read in its twelve 1 KB samples only, never downloaded,
 *   and only for books of the same size in KB (at most [MAX_CANDIDATES]). Two server books that are both
 *   the file link neither (which one holds the marks can't be told).
 * - Before every sync the local file is fingerprinted again (12 KB read); the server's is read again when
 *   its size in the library changed. A file changed on either side stops the sync; its marks stay here.
 * - A local book once linked links again only to the same book on the same server: its marks carry that
 *   book's ids, and syncing them with another book would mix the two.
 * - A book not on the server isn't checked again until the server has a new book of its size, or the
 *   file here changes.
 */
class LocalCopyMatcher(
    private val store: LocalCopyLinkStore,
    private val fingerprintOf: suspend (ReadableItem) -> LocalFileFingerprint?,
    private val serverFingerprintOf: suspend (bookId: String, sizeBytes: Long) -> RemoteFileFingerprint?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * [item]'s link to [serverKey]'s books, checked now. [canAskServer] false (offline) still settles what
     * needs no server: a file that changed, or one no server book has the size of.
     */
    suspend fun check(item: ReadableItem, serverKey: String, canAskServer: Boolean): LocalCopyCheck {
        val syncs = item.sourceType == SourceType.LOCAL && item.format.sharesBookmarksWithGrimmory()
        val file = if (syncs) fingerprintOf(item) else return LocalCopyCheck.NotLinked
        return file?.let { checkFile(Checking(item, serverKey, it), canAskServer) } ?: LocalCopyCheck.Unknown
    }

    private suspend fun checkFile(checking: Checking, canAskServer: Boolean): LocalCopyCheck {
        val link = store.link(checking.item.id)
        val onlyBook = link?.boundBookId(checking.serverKey)
        return when {
            // Linked once, on another server: never linked anywhere else.
            link != null && link.state != LOCAL_LINK_NOT_FOUND && onlyBook == null -> LocalCopyCheck.NotLinked
            link?.state == LOCAL_LINK_LINKED && link.isOf(checking.file) -> stillLinked(link, checking.file, canAskServer)
            else -> match(checking, link, onlyBook, canAskServer)
        }
    }

    // Not linked, or the file here changed since: find the server book that is this file, only [onlyBook] once linked.
    private suspend fun match(checking: Checking, link: LocalServerLinkEntity?, onlyBook: String?, canAskServer: Boolean): LocalCopyCheck {
        // The file here changed since it was linked: no more syncing until it is that book's file again.
        if (onlyBook != null && link?.state == LOCAL_LINK_LINKED) save(checking, onlyBook, LOCAL_LINK_CHANGED)
        val sameFileAsBefore = link != null && link.serverKey == checking.serverKey && link.isOf(checking.file)
        val rejected = if (sameFileAsBefore) link.rejected() else emptySet()
        val candidates = store.candidates(checking.item, checking.file.sizeBytes / BYTES_PER_KB)
            .filter { candidate -> (onlyBook == null || candidate.remoteId == onlyBook) && candidate.remoteId !in rejected }
            .take(MAX_CANDIDATES)
        return when {
            candidates.isEmpty() -> {
                if (onlyBook == null) save(checking, null, LOCAL_LINK_NOT_FOUND, rejected)
                LocalCopyCheck.NotLinked
            }
            !canAskServer -> LocalCopyCheck.Unknown
            else -> settle(checking, candidates, onlyBook, rejected)
        }
    }

    // Reads each candidate's samples; exactly one the same file links it.
    private suspend fun settle(
        checking: Checking,
        candidates: List<RemoteFileCandidate>,
        onlyBook: String?,
        rejected: Set<String>,
    ): LocalCopyCheck {
        val file = checking.file
        val found = candidates.filter { candidate -> serverFingerprintOf(candidate.remoteId, file.sizeBytes)?.isOf(file) == true }
            .singleOrNull()
        return when {
            found != null -> {
                save(checking, found.remoteId, LOCAL_LINK_LINKED, remoteFileSizeKb = found.remoteFileSizeKb)
                LocalCopyCheck.Linked(found.remoteId)
            }
            onlyBook != null -> {
                save(checking, onlyBook, LOCAL_LINK_CHANGED)
                LocalCopyCheck.NotLinked
            }
            else -> {
                save(checking, null, LOCAL_LINK_NOT_FOUND, rejected + candidates.map(RemoteFileCandidate::remoteId))
                LocalCopyCheck.NotLinked
            }
        }
    }

    // Linked, and the file here is as it was: still linked unless the server's book is gone or its file changed.
    private suspend fun stillLinked(link: LocalServerLinkEntity, file: LocalFileFingerprint, canAskServer: Boolean): LocalCopyCheck {
        val bookId = link.remoteBookId
        val book = bookId?.let { store.catalogBook(it) }
        return when {
            bookId == null -> LocalCopyCheck.NotLinked
            book == null -> LocalCopyCheck.Unknown
            book.remoteFileSizeKb == link.remoteFileSizeKb -> LocalCopyCheck.Linked(bookId)
            !canAskServer -> LocalCopyCheck.Unknown
            serverFingerprintOf(bookId, file.sizeBytes)?.isOf(file) == true -> {
                store.save(link.copy(remoteFileSizeKb = book.remoteFileSizeKb, checkedAtEpochMillis = now()))
                LocalCopyCheck.Linked(bookId)
            }
            else -> {
                store.save(link.copy(state = LOCAL_LINK_CHANGED, remoteFileSizeKb = book.remoteFileSizeKb, checkedAtEpochMillis = now()))
                LocalCopyCheck.NotLinked
            }
        }
    }

    private suspend fun save(
        checking: Checking,
        bookId: String?,
        state: String,
        rejected: Set<String> = emptySet(),
        remoteFileSizeKb: Long? = null,
    ) = store.save(
        LocalServerLinkEntity(
            localItemId = checking.item.id,
            serverKey = checking.serverKey,
            state = state,
            remoteBookId = bookId,
            fileSizeBytes = checking.file.sizeBytes,
            partialMd5 = checking.file.partialMd5,
            remoteFileSizeKb = remoteFileSizeKb,
            rejectedBookIds = rejected.sorted().joinToString(","),
            checkedAtEpochMillis = now(),
        ),
    )

    // One local book being checked against one server.
    private class Checking(val item: ReadableItem, val serverKey: String, val file: LocalFileFingerprint)

    companion object {
        /** Server books of the same size read at most, per local book. */
        const val MAX_CANDIDATES = 4
        private const val BYTES_PER_KB = 1024L
    }
}

/** The one book [this] may link to on [serverKey]'s server: its own, once it has been linked; null when it's free to match any. */
private fun LocalServerLinkEntity.boundBookId(serverKey: String): String? =
    remoteBookId?.takeIf { state != LOCAL_LINK_NOT_FOUND && this.serverKey == serverKey }

private fun LocalServerLinkEntity.isOf(file: LocalFileFingerprint): Boolean =
    fileSizeBytes == file.sizeBytes && partialMd5 == file.partialMd5

private fun LocalServerLinkEntity.rejected(): Set<String> = rejectedBookIds.split(',').filter(String::isNotEmpty).toSet()

private fun RemoteFileFingerprint.isOf(file: LocalFileFingerprint): Boolean =
    sizeBytes == file.sizeBytes && partialMd5 == file.partialMd5
