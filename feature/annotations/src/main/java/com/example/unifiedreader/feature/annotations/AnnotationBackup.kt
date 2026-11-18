package com.example.unifiedreader.feature.annotations

import com.example.unifiedreader.core.database.dao.ReaderAnnotationDao
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Settings › Back up to a file and Restore from a file: every highlight (with its note) and saved
 * word as one JSON file, and restoring one, which adds what this tablet doesn't have and changes
 * nothing it has.
 */
class AnnotationBackupStore(
    private val dao: ReaderAnnotationDao,
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
        )
        AnnotationBackupFile(fileName = annotationBackupFileName(at), text = encodeAnnotationBackup(backup))
    }

    /**
     * Adds the backup's highlights and words this tablet doesn't have: one with the same id, or the
     * same words in the same book, is left as it is here (a highlight removed here stays removed).
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
            plan.highlights.forEach(dao::upsertHighlight)
            plan.words.forEach(dao::upsertWord)
            AnnotationRestoreResult(
                addedHighlights = plan.highlights.size,
                addedWords = plan.words.size,
                alreadyHere = backup.highlights.size + backup.words.size - plan.highlights.size - plan.words.size,
            )
        }
    }
}

data class AnnotationBackupFile(val fileName: String, val text: String)

data class AnnotationRestoreResult(
    val addedHighlights: Int,
    val addedWords: Int,
    val alreadyHere: Int,
) {
    /** What the toast says: "Added 12 highlights and 3 words". */
    val message: String
        get() {
            val added = listOfNotNull(
                addedHighlights.takeIf { it > 0 }?.let { count -> if (count == 1) "1 highlight" else "$count highlights" },
                addedWords.takeIf { it > 0 }?.let { count -> if (count == 1) "1 word" else "$count words" },
            )
            return if (added.isEmpty()) "Nothing new: this tablet has it all" else "Added ${added.joinToString(" and ")}"
        }
}

class AnnotationBackupFormatException : IllegalArgumentException("Not a Katana backup")

/** The rows a restore writes: those of [backup] that this tablet has in no form. */
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

private fun ReaderHighlightEntity.sameWordsKey(): String = listOf(itemId, href, text.trim()).joinToString("\u0000")

private fun SavedWordEntity.sameWordKey(): String =
    listOf(itemId, word.trim().lowercase(Locale.ROOT), sentence?.trim().orEmpty()).joinToString("\u0000")

/** "Katana backup 2026-11-18.json". */
internal fun annotationBackupFileName(atEpochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val day = Instant.ofEpochMilli(atEpochMillis).atZone(zone).toLocalDate()
    return "Katana backup ${day.format(DateTimeFormatter.ISO_LOCAL_DATE)}.json"
}

private val BackupJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal fun encodeAnnotationBackup(backup: AnnotationBackup): String =
    BackupJson.encodeToString(AnnotationBackup.serializer(), backup)

internal fun decodeAnnotationBackup(text: String): AnnotationBackup {
    // Bad JSON and missing fields both fail the decoding; either way it isn't a backup this app can read.
    val backup = runCatching { BackupJson.decodeFromString(AnnotationBackup.serializer(), text) }.getOrNull()
    return backup?.takeIf { it.app == BACKUP_APP && it.version <= BACKUP_VERSION } ?: throw AnnotationBackupFormatException()
}

/** The file: what made it, its version, then the rows as the database keeps them. */
@Serializable
internal data class AnnotationBackup(
    val app: String = BACKUP_APP,
    val version: Int = BACKUP_VERSION,
    @SerialName("exportedAt") val exportedAtEpochMillis: Long,
    val highlights: List<BackupHighlight> = emptyList(),
    val words: List<BackupWord> = emptyList(),
)

@Serializable
internal data class BackupHighlight(
    val id: String,
    val itemId: String,
    val bookTitle: String,
    val bookAuthor: String? = null,
    val href: String,
    val locatorJson: String,
    val text: String,
    val chapterTitle: String? = null,
    val progression: Double? = null,
    val style: String,
    val color: String,
    val note: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val syncState: String = "DIRTY",
    val remoteId: String? = null,
    val remoteCfi: String? = null,
)

@Serializable
internal data class BackupWord(
    val id: String,
    val itemId: String,
    val bookTitle: String,
    val bookAuthor: String? = null,
    val word: String,
    val sentence: String? = null,
    val chapterTitle: String? = null,
    val locatorJson: String? = null,
    val progression: Double? = null,
    val note: String? = null,
    val createdAt: Long,
)

private fun ReaderHighlightEntity.toBackup() = BackupHighlight(
    id = id,
    itemId = itemId,
    bookTitle = bookTitle,
    bookAuthor = bookAuthor,
    href = href,
    locatorJson = locatorJson,
    text = text,
    chapterTitle = chapterTitle,
    progression = progression,
    style = style,
    color = color,
    note = note,
    createdAt = createdAtEpochMillis,
    updatedAt = updatedAtEpochMillis,
    syncState = syncState,
    remoteId = remoteId,
    remoteCfi = remoteCfi,
)

private fun BackupHighlight.toEntity() = ReaderHighlightEntity(
    id = id,
    itemId = itemId,
    bookTitle = bookTitle,
    bookAuthor = bookAuthor,
    href = href,
    locatorJson = locatorJson,
    text = text,
    chapterTitle = chapterTitle,
    progression = progression,
    style = style,
    color = color,
    note = note,
    createdAtEpochMillis = createdAt,
    updatedAtEpochMillis = updatedAt,
    deleted = false,
    syncState = syncState,
    remoteId = remoteId,
    remoteCfi = remoteCfi,
)

private fun SavedWordEntity.toBackup() = BackupWord(
    id = id,
    itemId = itemId,
    bookTitle = bookTitle,
    bookAuthor = bookAuthor,
    word = word,
    sentence = sentence,
    chapterTitle = chapterTitle,
    locatorJson = locatorJson,
    progression = progression,
    note = note,
    createdAt = createdAtEpochMillis,
)

private fun BackupWord.toEntity() = SavedWordEntity(
    id = id,
    itemId = itemId,
    bookTitle = bookTitle,
    bookAuthor = bookAuthor,
    word = word,
    sentence = sentence,
    chapterTitle = chapterTitle,
    locatorJson = locatorJson,
    progression = progression,
    note = note,
    createdAtEpochMillis = createdAt,
)

private const val BACKUP_APP = "Katana"
private const val BACKUP_VERSION = 1
