package com.example.unifiedreader

import android.net.Uri
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.feature.annotations.LocalBookDirectory
import com.example.unifiedreader.feature.annotations.LocalBookIdentity
import com.example.unifiedreader.feature.annotations.LocalBookMarksMover
import com.example.unifiedreader.feature.annotations.LocalReadingPlace
import com.example.unifiedreader.feature.annotations.LocalReadingPlaces
import com.example.unifiedreader.feature.annotations.PendingLocalMarks

/**
 * The books from files on this device, known by their files rather than by the picker's address: picking a file
 * that is already here (from another folder, or the same file again after a reinstall) opens the book already
 * here instead of adding a second copy, and a new book picks up the marks a restore kept for its file.
 * Everything else is [documents]'.
 */
class LocalBookImports(
    private val documents: LocalDocumentDataSource,
    private val hydrator: LocalDocumentHydrator,
    private val fingerprints: LocalFileFingerprinter,
    private val marksHoming: LocalMarksHoming? = null,
    /** Gives back the read permission taken for a picked file that turned out to be here already. */
    private val releaseRead: (Uri) -> Unit = {},
) : LocalDocumentDataSource by documents, LocalBookDirectory {
    override suspend fun import(uri: Uri): ReadableItem? {
        hydrator.hydrate()
        val existing = sameFileAlreadyHere(uri)
        if (existing != null) {
            releaseRead(uri)
            return existing
        }
        val known = documents.getImportedItems().mapTo(HashSet(), ReadableItem::id)
        val imported = documents.import(uri)
        if (imported != null && imported.id !in known) {
            marksHoming?.onImported(identityOf(imported))
        }
        return imported
    }

    /** Every book from a file here, with its file's fingerprint when the file can be read. */
    override suspend fun localBooks(): List<LocalBookIdentity> {
        hydrator.hydrate()
        return documents.getImportedItems().map { item -> identityOf(item) }
    }

    /** The book already here whose file is [uri]'s file (same size and partial MD5) under another address. */
    private suspend fun sameFileAlreadyHere(uri: Uri): ReadableItem? {
        val address = uri.toString()
        val others = documents.getImportedItems().filter { item -> documents.getDocumentUri(item)?.toString() != address }
        val fingerprint = if (others.isEmpty()) null else fingerprints.of(address)
        return fingerprint?.let { file ->
            others.firstOrNull { item -> documents.getDocumentUri(item)?.toString()?.let { fingerprints.of(it) } == file }
        }
    }

    private suspend fun identityOf(item: ReadableItem): LocalBookIdentity {
        val fingerprint = documents.getDocumentUri(item)?.toString()?.let { fingerprints.of(it) }
        return LocalBookIdentity(
            itemId = item.id,
            title = item.title,
            author = item.bookMetadata?.author,
            format = item.format.name,
            fileSize = fingerprint?.sizeBytes,
            partialMd5 = fingerprint?.partialMd5,
        )
    }
}

/**
 * After a book is imported from a file: the marks a restore kept for that same file under another id (the backup
 * was made before the book came back) move to it.
 */
class LocalMarksHoming(
    private val pending: PendingLocalMarks,
    private val mover: LocalBookMarksMover,
    /** Puts a waiting reading place back on the new book, only when newer than the one it has. */
    private val places: LocalReadingPlaces? = null,
) {
    /** How many marks moved to [book]. */
    suspend fun onImported(book: LocalBookIdentity): Int {
        if (!book.hasFingerprint) return 0
        val waiting = pending.takeSameFile(book)
        val moved = waiting
            .filter { it.itemId != book.itemId }
            .sumOf { old -> mover.moveMarks(fromItemId = old.itemId, toItemId = book.itemId) }
        if (waiting.isNotEmpty()) {
            pending.takePlaces(waiting.mapTo(HashSet(), LocalBookIdentity::itemId))
                .maxByOrNull(LocalReadingPlace::updatedAtEpochMillis)
                ?.let { place -> places?.restore(place.copy(itemId = book.itemId)) }
        }
        return moved
    }
}
