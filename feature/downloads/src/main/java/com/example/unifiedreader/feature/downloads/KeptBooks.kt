package com.example.unifiedreader.feature.downloads

import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.formatStorageSize
import com.example.unifiedreader.core.model.shortDayLabel
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A server's book whose file is on this tablet: downloaded, or kept when it was opened so it
 * reads offline. [openedAtEpochMillis] is when it was last read, null if it never was.
 */
data class KeptBook(
    val item: ReadableItem,
    val sizeBytes: Long,
    val openedAtEpochMillis: Long?,
    /** How far through, 0 to 100; null when the book has no place yet. */
    val readPercent: Int?,
    val isFinished: Boolean,
    /** It came through Download, not by opening it. */
    val isDownload: Boolean,
)

/** The books on the tablet, the latest read first, and the space left. */
data class KeptBooksSnapshot(
    val books: List<KeptBook> = emptyList(),
    val freeBytes: Long = 0L,
    val isLoaded: Boolean = false,
) {
    val totalBytes: Long
        get() = books.sumOf(KeptBook::sizeBytes)

    val finishedBooks: List<KeptBook>
        get() = books.filter(KeptBook::isFinished)

    companion object {
        val Empty = KeptBooksSnapshot()
    }
}

/** What the "On this tablet" section can do: [onRemove] takes the books' ids. */
data class KeptBookActions(
    val onRemove: (List<String>) -> Unit,
)

/** What one book on the tablet would be, before its file is measured. */
data class KeptBookCandidate(
    val item: ReadableItem,
    val openedAtEpochMillis: Long?,
    val readPercent: Int?,
    val isFinished: Boolean,
    val isDownload: Boolean,
)

/**
 * The files each server keeps for a book: the downloaded or opened copy its download source
 * stores, plus any [extraFiles] (Grimmory's reader cache), so the size counts them all and
 * Remove takes them all.
 */
class KeptBookFiles(
    private val dataSources: List<RemoteItemDownloadDataSource>,
    private val extraFiles: (ReadableItem) -> List<File> = { emptyList() },
) {
    fun filesFor(item: ReadableItem): List<File> {
        val stored = dataSources.downloadDataSourceFor(item)
            ?.storedDocumentUri(item)
            ?.takeIf { uri -> uri.scheme == "file" }
            ?.path
            ?.let(::File)
        return (listOfNotNull(stored) + extraFiles(item)).filter(File::exists).distinctBy(File::getAbsolutePath)
    }

    /** The candidates that have a file here, measured, the latest read first. Call off the main thread. */
    fun measure(candidates: List<KeptBookCandidate>): List<KeptBook> = candidates
        .distinctBy { candidate -> candidate.item.id }
        .mapNotNull { candidate ->
            val size = filesFor(candidate.item).sumOf(File::sizeOnDisk)
            if (size <= 0L) return@mapNotNull null
            KeptBook(
                item = candidate.item,
                sizeBytes = size,
                openedAtEpochMillis = candidate.openedAtEpochMillis,
                readPercent = candidate.readPercent,
                isFinished = candidate.isFinished,
                isDownload = candidate.isDownload,
            )
        }
        .sortedWith(keptBookOrder)

    /** Deletes what the tablet keeps for [item]; true when something went. */
    suspend fun delete(item: ReadableItem): Boolean {
        val stored = dataSources.downloadDataSourceFor(item)?.deleteStoredDocument(item) == true
        val extras = withContext(Dispatchers.IO) { extraFiles(item).filter(File::exists).count(File::deleteRecursively) }
        return stored || extras > 0
    }
}

/** Read latest first; never-read downloads after, by title. */
internal val keptBookOrder: Comparator<KeptBook> =
    compareByDescending<KeptBook> { book -> book.openedAtEpochMillis ?: Long.MIN_VALUE }
        .thenBy { book -> book.item.title.lowercase() }

/** A file's size, or a folder's (a comic downloaded as pages). */
internal fun File.sizeOnDisk(): Long = if (isDirectory) walkTopDown().filter(File::isFile).sumOf(File::length) else length()

/** "2.1 MB · opened today · 9% read". */
fun KeptBook.sheetLine(
    nowEpochMillis: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): String = listOfNotNull(
    formatStorageSize(sizeBytes),
    openedLabel(nowEpochMillis, zone),
    progressLabel(),
).joinToString(" · ")

private fun KeptBook.openedLabel(nowEpochMillis: Long, zone: ZoneId): String {
    val opened = openedAtEpochMillis ?: return if (isDownload) "downloaded, not opened yet" else "not opened yet"
    val day = Instant.ofEpochMilli(opened).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowEpochMillis).atZone(zone).toLocalDate()
    return when (day) {
        today -> "opened today"
        today.minusDays(1) -> "opened yesterday"
        else -> "opened ${shortDayLabel(day, today)}"
    }
}

private fun KeptBook.progressLabel(): String? = when {
    isFinished -> "finished"
    readPercent == null || openedAtEpochMillis == null -> null
    readPercent < 1 -> "just started"
    else -> "$readPercent% read"
}

/** The sheet's line under its title: "3 books on this tablet · 8.5 MB". */
fun keptBooksSummary(snapshot: KeptBooksSnapshot): String? {
    if (!snapshot.isLoaded || snapshot.books.isEmpty()) return null
    val count = snapshot.books.size
    val books = if (count == 1) "1 book" else "$count books"
    return "$books on this tablet · ${formatStorageSize(snapshot.totalBytes)}"
}

/** The share of the tablet's space these books take, for the bar under the list. */
internal fun keptBooksShare(snapshot: KeptBooksSnapshot): Float {
    val total = snapshot.totalBytes
    val whole = total + snapshot.freeBytes
    return if (whole <= 0L) 0f else (total.toDouble() / whole).toFloat()
}
