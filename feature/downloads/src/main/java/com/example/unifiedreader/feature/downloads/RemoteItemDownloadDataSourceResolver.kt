package com.example.unifiedreader.feature.downloads

import com.example.unifiedreader.core.model.ReadableItem

fun List<RemoteItemDownloadDataSource>.downloadDataSourceFor(
    item: ReadableItem,
): RemoteItemDownloadDataSource? = firstOrNull { dataSource ->
    dataSource.supports(item)
}

/** Forgets the bytes kept by [entry]'s unfinished files, for an entry that won't go on (canceled or removed). */
internal suspend fun List<RemoteItemDownloadDataSource>.forgetPartialDownloads(
    entry: DownloadEntry,
) {
    entry.actionItems
        .filter { actionItem -> actionItem.id !in entry.completedItemIds }
        .map(QueuedDownloadItem::toReadableItem)
        .forEach { item -> downloadDataSourceFor(item)?.deletePartialDownload(item) }
}
