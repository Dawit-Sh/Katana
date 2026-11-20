package com.example.unifiedreader.feature.annotations

import kotlinx.serialization.Serializable

/*
 * The parts of the backup file about books from files on the device that made it: each book with its file's
 * fingerprint, and each book's reading place. Older backups have neither; both lists default to empty.
 */

/** A book from a file on the device that made the backup, so a restore can find it again under another id. */
@Serializable
internal data class BackupBook(
    val itemId: String,
    val title: String,
    val author: String? = null,
    val format: String,
    val fileSize: Long? = null,
    val partialMd5: String? = null,
)

internal fun LocalBookIdentity.toBackup() = BackupBook(
    itemId = itemId,
    title = title,
    author = author,
    format = format,
    fileSize = fileSize,
    partialMd5 = partialMd5,
)

internal fun BackupBook.toIdentity() = LocalBookIdentity(
    itemId = itemId,
    title = title,
    author = author,
    format = format,
    fileSize = fileSize,
    partialMd5 = partialMd5,
)

/** A book from a file's reading place. */
@Serializable
internal data class BackupPlace(
    val itemId: String,
    val locatorType: String,
    val page: Int? = null,
    val cfi: String? = null,
    val percent: Double? = null,
    val isFinished: Boolean = false,
    val updatedAt: Long,
)

internal fun LocalReadingPlace.toBackup() = BackupPlace(
    itemId = itemId,
    locatorType = locatorType,
    page = page,
    cfi = cfi,
    percent = percent,
    isFinished = isFinished,
    updatedAt = updatedAtEpochMillis,
)

internal fun BackupPlace.toPlace(itemId: String = this.itemId) = LocalReadingPlace(
    itemId = itemId,
    locatorType = locatorType,
    page = page,
    cfi = cfi,
    percent = percent,
    isFinished = isFinished,
    updatedAtEpochMillis = updatedAt,
)
