package com.example.unifiedreader

import android.content.Context
import android.net.Uri
import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.core.network.PartialDownloadFile
import com.example.unifiedreader.feature.downloads.RemoteItemDownloadDataSource
import com.example.unifiedreader.feature.downloads.RemoteItemDownloadResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A server's books downloaded to this device, one file each in [storageDirectoryName]. A download in progress
 * lives apart, in `download-partials/`, until it is whole: one that breaks off (the Wi-Fi drops, the system
 * stops the work) goes on from its bytes next time, and a half file is never taken for a book.
 */
abstract class RemoteDocumentDownloadRepository<Session : StoredSourceSession>(
    private val context: Context,
    final override val sourceType: SourceType,
    private val storageDirectoryName: String,
    private val supportedFormats: Set<FormatType>,
    private val sessionStore: SourceSessionStore<Session>,
    private val missingSessionMessage: String,
) : RemoteItemDownloadDataSource {
    private val documentDownloadMutex = Mutex()
    private val partialDownloads = PartialDownloads(
        File(File(context.applicationContext.filesDir, PARTIAL_DOWNLOADS_DIRECTORY_NAME), storageDirectoryName),
    )

    final override fun supports(
        item: ReadableItem,
    ): Boolean = item.sourceType == sourceType && item.format in supportedFormats

    final override fun storedDocumentUri(
        item: ReadableItem,
    ): Uri? {
        if (!supports(item)) return null
        val file = item.documentFile
        return if (file.isFile && file.length() > 0L) {
            Uri.fromFile(file)
        } else {
            null
        }
    }

    final override suspend fun downloadToLocalStorage(
        item: ReadableItem,
    ): Uri? = prepareDocumentForReading(item)?.uri

    final override suspend fun prepareDocumentForReading(
        item: ReadableItem,
    ): RemoteItemDownloadResult? {
        if (!supports(item)) return null
        storedDocumentUri(item)?.let { uri ->
            return RemoteItemDownloadResult(
                uri = uri,
                downloadedFromNetwork = false,
            )
        }
        val file = item.documentFile
        var downloadedFromNetwork = false
        documentDownloadMutex.withLock {
            storedDocumentUri(item)?.let { return@withLock }
            val session = sessionStore.activeSessionFor(item) ?: error(missingSessionMessage)
            val remoteItemId = item.remoteId ?: error("Unable to resolve ${sourceType.displayName()} item id.")
            val partial = partialDownloads.forBook(file)
            withContext(Dispatchers.IO) { partialDownloads.forgetAbandoned(keep = partial) }
            download(
                session = session,
                item = item,
                remoteItemId = remoteItemId,
                target = partial,
            )
            withContext(Dispatchers.IO) {
                partial.moveInto(file, targetDescription = "downloaded ${sourceType.displayName()} file for ${item.title}")
            }
            onStoredDocumentChanged(item)
            downloadedFromNetwork = true
        }
        return storedDocumentUri(item)?.let { uri ->
            RemoteItemDownloadResult(
                uri = uri,
                downloadedFromNetwork = downloadedFromNetwork,
            )
        }
    }

    final override suspend fun storedDocumentItemIds(
        items: List<ReadableItem>,
    ): Set<String> = withContext(Dispatchers.IO) {
        items
            .filter { item -> storedDocumentUri(item) != null }
            .mapTo(mutableSetOf(), ReadableItem::id)
    }

    final override suspend fun deletePartialDownload(
        item: ReadableItem,
    ) = withContext(Dispatchers.IO) {
        if (supports(item)) partialDownloads.forBook(item.documentFile).delete()
    }

    final override suspend fun deleteStoredDocument(
        item: ReadableItem,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!supports(item)) return@withContext false
        val file = item.documentFile
        partialDownloads.forBook(file).delete()
        val deleted = file.exists() && file.delete()
        if (deleted) {
            onStoredDocumentChanged(item)
        }
        deleted
    }

    /**
     * Downloads [item] into [target], going on after the bytes it already holds when the server allows; on
     * return [target] holds the whole file. When the connection breaks off, [target] keeps what arrived.
     */
    protected abstract suspend fun download(
        session: Session,
        item: ReadableItem,
        remoteItemId: String,
        target: PartialDownloadFile,
    )

    protected open fun onStoredDocumentChanged(
        item: ReadableItem,
    ) = Unit

    protected open fun cacheFileName(
        item: ReadableItem,
        extension: String,
    ): String = item.remoteDocumentCacheFileName(extension)

    // Where the book is kept once downloaded, there or not.
    private val ReadableItem.documentFile: File
        get() = File(
            File(context.applicationContext.filesDir, storageDirectoryName),
            cacheFileName(item = this, extension = format.name.lowercase()),
        )
}

private const val PARTIAL_DOWNLOADS_DIRECTORY_NAME = "download-partials"

internal fun ReadableItem.remoteDocumentCacheFileName(
    extension: String,
): String = listOfNotNull(
    id.toSafeFileSegment(),
    remoteFileId?.toSafeFileSegment(),
).joinToString(separator = "-").plus(".$extension")

private fun SourceType.displayName(): String = name.lowercase()
