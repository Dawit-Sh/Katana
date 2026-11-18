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
 * what this device doesn't have and changes nothing it has.
 */
class AnnotationBackupStore(
    private val dao: ReaderAnnotationDao,
    private val bookmarkDao: ReaderBookmarkDao? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** The backup file's name and text. */
    suspend fun backUp(): AnnotationBackupFile = withContext(ioDispatcher) {
        val at = now()
        val backup = AnnotationBackup(
            exportedAtEpochMillis = at,
            highlights = dao.observeAllHighlights().first().map(ReaderHighlightEntity::toBackup),
            words = dao.observeAllWords().first().map(SavedWordEntity::toBackup),
            bookmarks = bookmarkDao?.observeAllBookmarks()?.first().orEmpty().map(ReaderBookmarkEntity::toBackup),
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
        val backup = decodeAnnotationBackup(text)
        return withContext(ioDispatcher) {
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
            AnnotationRestoreResult(
                addedHighlights = plan.highlights.size,
                addedWords = plan.words.size,
                addedBookmarks = bookmarks.size,
                alreadyHere = backup.highlights.size + backup.words.size + backup.bookmarks.size - added,
            )
        }
    }
}

data class AnnotationBackupFile(val fileName: String, val text: String)

data class AnnotationRestoreResult(
    val addedHighlights: Int,
    val addedWords: Int,
    val alreadyHere: Int,
    val addedBookmarks: Int = 0,
) {
    /** What the toast says: "Added 12 highlights, 3 words and 2 bookmarks". */
    val message: String
        get() {
            val added = listOfNotNull(
                counted(addedHighlights, "highlight"),
                counted(addedWords, "word"),
                counted(addedBookmarks, "bookmark"),
            )
            return when (added.size) {
                0 -> "Nothing new: this device has it all"
                1 -> "Added ${added.single()}"
                else -> "Added ${added.dropLast(1).joinToString(", ")} and ${added.last()}"
            }
        }

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
