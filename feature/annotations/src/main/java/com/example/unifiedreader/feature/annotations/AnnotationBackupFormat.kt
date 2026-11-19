package com.example.unifiedreader.feature.annotations

import com.example.unifiedreader.core.database.entity.ReaderBookmarkEntity
import com.example.unifiedreader.core.database.entity.ReaderHighlightEntity
import com.example.unifiedreader.core.database.entity.SavedWordEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * The backup file's format: JSON with what made it ("Katana"), its version, then the highlights,
 * saved words and bookmarks as the database keeps them. A newer version is refused; fields added
 * later have defaults, so an older file still reads.
 */

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
    val bookmarks: List<BackupBookmark> = emptyList(),
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
    val reviewLevel: Int = 0,
    val reviewDueAt: Long? = null,
    val reviewedAt: Long? = null,
)

@Serializable
internal data class BackupBookmark(
    val id: String,
    val itemId: String,
    val bookTitle: String,
    val bookAuthor: String? = null,
    val href: String,
    val locatorJson: String,
    val chapterTitle: String? = null,
    val progression: Double? = null,
    val page: Int? = null,
    val pageCount: Int? = null,
    val text: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val syncState: String = "DIRTY",
    val remoteId: String? = null,
    val remoteCfi: String? = null,
    val title: String? = null,
    val note: String? = null,
)

internal fun ReaderHighlightEntity.toBackup() = BackupHighlight(
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

internal fun BackupHighlight.toEntity() = ReaderHighlightEntity(
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

internal fun SavedWordEntity.toBackup() = BackupWord(
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
    reviewLevel = reviewLevel,
    reviewDueAt = reviewDueAtEpochMillis,
    reviewedAt = reviewedAtEpochMillis,
)

internal fun BackupWord.toEntity() = SavedWordEntity(
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
    reviewLevel = reviewLevel,
    reviewDueAtEpochMillis = reviewDueAt,
    reviewedAtEpochMillis = reviewedAt,
)

internal fun ReaderBookmarkEntity.toBackup() = BackupBookmark(
    id = id,
    itemId = itemId,
    bookTitle = bookTitle,
    bookAuthor = bookAuthor,
    href = href,
    locatorJson = locatorJson,
    chapterTitle = chapterTitle,
    progression = progression,
    page = page,
    pageCount = pageCount,
    text = text,
    createdAt = createdAtEpochMillis,
    updatedAt = updatedAtEpochMillis,
    syncState = syncState,
    remoteId = remoteId,
    remoteCfi = remoteCfi,
    title = title,
    note = note,
)

internal fun BackupBookmark.toEntity() = ReaderBookmarkEntity(
    id = id,
    itemId = itemId,
    bookTitle = bookTitle,
    bookAuthor = bookAuthor,
    href = href,
    locatorJson = locatorJson,
    chapterTitle = chapterTitle,
    progression = progression,
    page = page,
    pageCount = pageCount,
    text = text,
    createdAtEpochMillis = createdAt,
    updatedAtEpochMillis = updatedAt,
    deleted = false,
    syncState = syncState,
    remoteId = remoteId,
    remoteCfi = remoteCfi,
    title = title,
    note = note,
)

internal const val BACKUP_APP = "Katana"
internal const val BACKUP_VERSION = 1
