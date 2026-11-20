package com.example.unifiedreader.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** The file here is the very same file as server book [LocalServerLinkEntity.remoteBookId]: its marks sync with it. */
const val LOCAL_LINK_LINKED = "LINKED"

/** No book on the server is the same file (the candidates checked are in [LocalServerLinkEntity.rejectedBookIds]). */
const val LOCAL_LINK_NOT_FOUND = "NOT_FOUND"

/**
 * It was linked, and then the file here or the server's changed: its marks stay on this device, and it
 * links again only to the same server book ([LocalServerLinkEntity.remoteBookId] is kept).
 */
const val LOCAL_LINK_CHANGED = "CHANGED"

/**
 * A local book ([localItemId]) checked against a server's books for the very same file: same format,
 * exact size and partial MD5 ([fileSizeBytes] and [partialMd5] are the local file's when it was checked).
 * [serverKey] is the server and account it was checked against; [remoteFileSizeKb] the server file's size
 * then. [rejectedBookIds] (comma-separated) are the books of the same size found to be other files, so they
 * are not read again.
 */
@Entity(tableName = "local_server_links")
data class LocalServerLinkEntity(
    @PrimaryKey val localItemId: String,
    val serverKey: String,
    val state: String,
    val remoteBookId: String?,
    val fileSizeBytes: Long,
    val partialMd5: String,
    val remoteFileSizeKb: Long?,
    val rejectedBookIds: String,
    val checkedAtEpochMillis: Long,
)

/** A server book of one size and format, as a local file is matched against it. */
data class RemoteFileCandidate(
    val remoteId: String,
    val remoteFileSizeKb: Long?,
)
