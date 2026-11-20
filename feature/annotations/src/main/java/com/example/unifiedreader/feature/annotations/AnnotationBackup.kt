package com.example.unifiedreader.feature.annotations

import com.example.unifiedreader.core.database.dao.ReaderAnnotationDao
import com.example.unifiedreader.core.database.dao.ReaderBookmarkDao
import com.example.unifiedreader.core.database.entity.ReaderBookmarkEntity
import com.example.unifiedreader.core.database.entity.ReaderHighlightEntity
import com.example.unifiedreader.core.database.entity.SavedWordEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Settings › Back up to a file and Restore from a file: every highlight (with its note), saved
 * word (with its review state) and page bookmark as one JSON file, and restoring one, which adds
 * what this device doesn't have and changes nothing it has. A book from a file is kept with its file's
 * fingerprint, so a restore finds it again when it came back under another id ([planLocalRehome]).
 */
class AnnotationBackupStore(
    private val dao: ReaderAnnotationDao,
    private val bookmarkDao: ReaderBookmarkDao? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    /** The books from files here, their waiting marks and reading places; none leaves them out. */
    private val local: LocalBooksInBackup? = null,
) {
    private val localBooks: LocalBookDirectory? get() = local?.directory
    private val pendingLocalMarks: PendingLocalMarks? get() = local?.pendingMarks
    private val readingPlaces: LocalReadingPlaces? get() = local?.readingPlaces

    /** The backup file's name and text. */
    suspend fun backUp(): AnnotationBackupFile = withContext(ioDispatcher) {
        val at = now()
        val highlights = dao.observeAllHighlights().first().map(ReaderHighlightEntity::toBackup)
        val words = dao.observeAllWords().first().map(SavedWordEntity::toBackup)
        val bookmarks = bookmarkDao?.observeAllBookmarks()?.first().orEmpty().map(ReaderBookmarkEntity::toBackup)
        val places = readingPlaces?.places().orEmpty().filter { isLocalBookId(it.itemId) }
        val localIds = (highlights.map(BackupHighlight::itemId) + words.map(BackupWord::itemId) + bookmarks.map(BackupBookmark::itemId))
            .plus(places.map(LocalReadingPlace::itemId))
            .filterTo(HashSet(), ::isLocalBookId)
        val books = if (localIds.isEmpty()) emptyList() else localBooks?.localBooks().orEmpty().filter { it.itemId in localIds }
        val backup = AnnotationBackup(
            exportedAtEpochMillis = at,
            highlights = highlights,
            words = words,
            bookmarks = bookmarks,
            books = books.map(LocalBookIdentity::toBackup),
            places = places.map(LocalReadingPlace::toBackup),
        )
        AnnotationBackupFile(fileName = annotationBackupFileName(at), text = encodeAnnotationBackup(backup))
    }

    /**
     * Adds the backup's highlights, words and bookmarks this device doesn't have: one with the same
     * id, or the same words (or page) in the same book, is left as it is here. A highlight removed
     * here stays removed; a bookmark removed here comes back.
     * Throws [AnnotationBackupFormatException] when [text] isn't a Katana backup.
     */
    suspend fun restore(text: String): AnnotationRestoreResult {
        val decoded = decodeAnnotationBackup(text)
        return withContext(ioDispatcher) {
            val names = decoded.markedBookNames()
            val here = if (names.keys.any(::isLocalBookId)) localBooks?.localBooks() else null
            val rehome = here?.let { books -> planLocalRehome(names, decoded.books.map(BackupBook::toIdentity), books) }
                ?: LocalRehomePlan()
            // Marks already here under an old id (a restore before this version, or a file picked again) go with them.
            val movedHere = rehome.newIds.entries.sumOf { (from, to) ->
                local?.marksMover?.moveMarks(from, to) ?: dao.moveMarks(from, to)
            }
            val backup = decoded.rehomed(rehome)
            val restoredPlaces = restorePlaces(decoded.places, rehome, here.orEmpty())
            val waitingBooks = decoded.books.map(BackupBook::toIdentity).filter { it.itemId in rehome.notHere && it.hasFingerprint }
            pendingLocalMarks?.remember(waitingBooks)
            // The places of those books come back when their files are imported, as their marks do.
            val waitingIds = waitingBooks.mapTo(HashSet(), LocalBookIdentity::itemId)
            pendingLocalMarks?.rememberPlaces(decoded.places.filter { it.itemId in waitingIds }.map { it.toPlace() })
            val plan = planAnnotationRestore(
                backup = backup,
                existingHighlights = dao.observeAllHighlights().first(),
                existingWords = dao.observeAllWords().first(),
                knownHighlightId = { id -> dao.getHighlight(id) != null },
            )
            val bookmarks = bookmarkDao?.let { bookmarkStore ->
                planBookmarkRestore(backup.bookmarks, bookmarkStore.observeAllBookmarks().first())
            }.orEmpty()
            plan.highlights.forEach(dao::upsertHighlight)
            plan.words.forEach(dao::upsertWord)
            bookmarks.forEach { bookmark -> bookmarkDao?.upsertBookmark(bookmark) }
            val added = plan.highlights.size + plan.words.size + bookmarks.size
            val addedIds = plan.highlights.map(ReaderHighlightEntity::itemId) + plan.words.map(SavedWordEntity::itemId) +
                bookmarks.map(ReaderBookmarkEntity::itemId)
            AnnotationRestoreResult(
                addedHighlights = plan.highlights.size,
                addedWords = plan.words.size,
                addedBookmarks = bookmarks.size,
                alreadyHere = backup.highlights.size + backup.words.size + backup.bookmarks.size - added,
                forBooksNotHere = addedIds.count { it in rehome.notHere },
                forUnsureBooks = addedIds.count { it in rehome.unsure },
                restoredPlaces = restoredPlaces,
                movedToTheirBook = movedHere,
            )
        }
    }

    /** Puts back the places of books from files that are here (under [rehome]'s ids), each only when newer. */
    private suspend fun restorePlaces(places: List<BackupPlace>, rehome: LocalRehomePlan, here: List<LocalBookIdentity>): Int {
        val store = readingPlaces ?: return 0
        val hereIds = here.mapTo(HashSet(), LocalBookIdentity::itemId)
        return places
            .map { place -> place.toPlace(itemId = rehome.idFor(place.itemId)) }
            .filter { place -> isLocalBookId(place.itemId) && place.itemId in hereIds }
            .count { place -> store.restore(place) }
    }
}

/** The title and author each book's marks (and each book's place) carry, by the book's id. */
private fun AnnotationBackup.markedBookNames(): Map<String, MarkedBookName> = buildMap {
    highlights.forEach { putIfAbsent(it.itemId, MarkedBookName(it.bookTitle, it.bookAuthor)) }
    bookmarks.forEach { putIfAbsent(it.itemId, MarkedBookName(it.bookTitle, it.bookAuthor)) }
    words.forEach { putIfAbsent(it.itemId, MarkedBookName(it.bookTitle, it.bookAuthor)) }
    books.forEach { putIfAbsent(it.itemId, MarkedBookName(it.title, it.author)) }
}

/** The backup with its marks moved to the books here that [plan] found for them. */
private fun AnnotationBackup.rehomed(plan: LocalRehomePlan): AnnotationBackup =
    if (plan.newIds.isEmpty()) {
        this
    } else {
        copy(
            highlights = highlights.map { it.copy(itemId = plan.idFor(it.itemId)) },
            words = words.map { it.copy(itemId = plan.idFor(it.itemId)) },
            bookmarks = bookmarks.map { it.copy(itemId = plan.idFor(it.itemId)) },
        )
    }

data class AnnotationBackupFile(val fileName: String, val text: String)

data class AnnotationRestoreResult(
    val addedHighlights: Int,
    val addedWords: Int,
    val alreadyHere: Int,
    val addedBookmarks: Int = 0,
    /** Of those added, the marks of books from files that aren't on this device (yet). */
    val forBooksNotHere: Int = 0,
    /** Of those added, the marks left as they were because more than one book here could be theirs. */
    val forUnsureBooks: Int = 0,
    /** Reading places of books from files put back (each only where it was newer than this device's). */
    val restoredPlaces: Int = 0,
    /** Marks that were already here under a book's old id and now sit with the book again. */
    val movedToTheirBook: Int = 0,
) {
    /**
     * What the toast says: "Added 12 highlights, 3 words and 2 bookmarks", and, when some are for books
     * from files that aren't here or could be either of two books here, how many.
     */
    val message: String
        get() {
            val added = listOfNotNull(
                counted(addedHighlights, "highlight"),
                counted(addedWords, "word"),
                counted(addedBookmarks, "bookmark"),
                counted(restoredPlaces, "reading place"),
            )
            val summary = when {
                added.isEmpty() && movedToTheirBook > 0 -> "Nothing new to add"
                added.isEmpty() -> "Nothing new: this device has it all"
                added.size == 1 -> "Added ${added.single()}"
                else -> "Added ${added.dropLast(1).joinToString(", ")} and ${added.last()}"
            }
            val notes = listOfNotNull(
                movedToTheirBook.takeIf { it > 0 }?.let { moved ->
                    if (moved == 1) "1 mark here is back with its book" else "$moved marks here are back with their book"
                },
                forBooksNotHere.takeIf { it > 0 }?.let { "${it.areOrIs()} for books not on this device" },
                forUnsureBooks.takeIf { it > 0 }?.let {
                    "${it.areOrIs()} for a book this device has more than one copy of, so they were left as they were"
                },
            )
            return (listOf(summary) + notes).joinToString(". ")
        }

    private fun Int.areOrIs(): String = if (this == 1) "1 is" else "$this are"

    private fun counted(count: Int, thing: String): String? = when (count) {
        0 -> null
        1 -> "1 $thing"
        else -> "$count ${thing}s"
    }
}

class AnnotationBackupFormatException : IllegalArgumentException("Not a Katana backup")

/** The rows a restore writes: those of [backup] that this device has in no form. */
internal data class AnnotationRestorePlan(
    val highlights: List<ReaderHighlightEntity>,
    val words: List<SavedWordEntity>,
)

internal fun planAnnotationRestore(
    backup: AnnotationBackup,
    existingHighlights: List<ReaderHighlightEntity>,
    existingWords: List<SavedWordEntity>,
    knownHighlightId: (String) -> Boolean,
): AnnotationRestorePlan {
    val highlightKeys = existingHighlights.mapTo(mutableSetOf(), ReaderHighlightEntity::sameWordsKey)
    val highlightIds = existingHighlights.mapTo(mutableSetOf(), ReaderHighlightEntity::id)
    val highlights = backup.highlights
        .map(BackupHighlight::toEntity)
        .filter { row ->
            row.id !in highlightIds &&
                !knownHighlightId(row.id) &&
                highlightKeys.add(row.sameWordsKey()) &&
                highlightIds.add(row.id)
        }
    val wordKeys = existingWords.mapTo(mutableSetOf(), SavedWordEntity::sameWordKey)
    val wordIds = existingWords.mapTo(mutableSetOf(), SavedWordEntity::id)
    val words = backup.words
        .map(BackupWord::toEntity)
        .filter { row -> wordIds.add(row.id) && wordKeys.add(row.sameWordKey()) }
    return AnnotationRestorePlan(highlights = highlights, words = words)
}

/** The backup's bookmarks this device doesn't have: not the same id, not the same page of the same book. */
internal fun planBookmarkRestore(
    backup: List<BackupBookmark>,
    existing: List<ReaderBookmarkEntity>,
): List<ReaderBookmarkEntity> {
    val ids = existing.mapTo(mutableSetOf(), ReaderBookmarkEntity::id)
    val pages = existing.mapTo(mutableSetOf(), ReaderBookmarkEntity::samePageKey)
    return backup.map(BackupBookmark::toEntity).filter { row -> ids.add(row.id) && pages.add(row.samePageKey()) }
}

private fun ReaderBookmarkEntity.samePageKey(): String =
    listOf(itemId, href, progression?.toString().orEmpty()).joinToString("\u0000")

private fun ReaderHighlightEntity.sameWordsKey(): String = listOf(itemId, href, text.trim()).joinToString("\u0000")

private fun SavedWordEntity.sameWordKey(): String =
    listOf(itemId, word.trim().lowercase(Locale.ROOT), sentence?.trim().orEmpty()).joinToString("\u0000")

/** "Katana backup 2026-11-18.json". */
internal fun annotationBackupFileName(atEpochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val day = Instant.ofEpochMilli(atEpochMillis).atZone(zone).toLocalDate()
    return "Katana backup ${day.format(DateTimeFormatter.ISO_LOCAL_DATE)}.json"
}
