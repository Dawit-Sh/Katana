package com.example.unifiedreader.feature.annotations

import com.example.unifiedreader.core.model.clampedToNowIfBeyondLimit
import java.util.Locale

/**
 * A book on this device that came from a file, as a backup keeps it and a restore matches marks to it: its id
 * here, its title, author and format, and its file's size and partial MD5 when the file could be read.
 */
data class LocalBookIdentity(
    val itemId: String,
    val title: String,
    val author: String?,
    val format: String,
    val fileSize: Long? = null,
    val partialMd5: String? = null,
) {
    val hasFingerprint: Boolean
        get() = fileSize != null && !partialMd5.isNullOrBlank()

    /** The same file: same size and partial MD5 (another copy of it, or the same one picked again). */
    fun sameFileAs(other: LocalBookIdentity): Boolean =
        hasFingerprint && other.hasFingerprint && fileSize == other.fileSize && partialMd5.equals(other.partialMd5, ignoreCase = true)
}

/** The books from files on this device, for a backup to describe and a restore to match marks to. */
fun interface LocalBookDirectory {
    suspend fun localBooks(): List<LocalBookIdentity>
}

/**
 * What a backup and a restore know of the books from files on this device: the books with their files'
 * fingerprints ([directory]), where a restore leaves the marks of files not here yet ([pendingMarks]), and the
 * books' reading places ([readingPlaces]).
 */
class LocalBooksInBackup(
    val directory: LocalBookDirectory,
    val pendingMarks: PendingLocalMarks? = null,
    val readingPlaces: LocalReadingPlaces? = null,
    /** Moves marks already on this device under a book's old id to the book here (an earlier restore's, say). */
    val marksMover: LocalBookMarksMover? = null,
)

/**
 * Marks a restore couldn't give to a book here because their book's file isn't here yet: the next time that
 * file is imported, its marks move to it ([LocalBookMarksMover]).
 */
interface PendingLocalMarks {
    /** Remembers [books] (with their files' fingerprints) until their files are imported. */
    suspend fun remember(books: List<LocalBookIdentity>)

    /** The remembered books whose file is [file], forgotten as they are returned. */
    suspend fun takeSameFile(file: LocalBookIdentity): List<LocalBookIdentity>
}

/** Moves a book's marks to another id; the number of marks moved. */
fun interface LocalBookMarksMover {
    suspend fun moveMarks(fromItemId: String, toItemId: String): Int
}

/** Where a book from a file was being read, as a backup keeps it ([LocalReadingPlaces]). */
data class LocalReadingPlace(
    val itemId: String,
    val locatorType: String,
    val page: Int? = null,
    val cfi: String? = null,
    val percent: Double? = null,
    val isFinished: Boolean = false,
    val updatedAtEpochMillis: Long,
)

/** The reading places of books from files: a server keeps its own books' places, so those are never here. */
interface LocalReadingPlaces {
    suspend fun places(): List<LocalReadingPlace>

    /** Puts [place] back unless the book isn't here or this device has a newer place for it; whether it did. */
    suspend fun restore(place: LocalReadingPlace): Boolean
}

/**
 * Whether a backed-up place saved at [backupAt] should replace this device's place saved at [currentAt] (null when
 * it has none): only when it is newer. A time more than a day ahead of [now] (a wrong clock) counts as now.
 */
fun isNewerPlace(backupAt: Long, currentAt: Long?, now: Long): Boolean =
    currentAt == null || backupAt.clampedToNowIfBeyondLimit(now) > currentAt

/** Whether [itemId] names a book imported from a file on this device ("document-1a2b3c", "comic-…"); a server's never does. */
fun isLocalBookId(itemId: String): Boolean = LocalBookIdPattern.matches(itemId)

private val LocalBookIdPattern = Regex("(document|comic)-[0-9a-f]{1,8}")

/** The title and author the marks of one book carry. */
internal data class MarkedBookName(val title: String, val author: String?)

/** Which books' marks move to which book here, and the books they couldn't be given to. */
internal data class LocalRehomePlan(
    val newIds: Map<String, String> = emptyMap(),
    /** Books from files with no book here to take their marks. */
    val notHere: Set<String> = emptySet(),
    /** Books from files with more than one book here they could be (two editions, say): left as they were. */
    val unsure: Set<String> = emptySet(),
) {
    fun idFor(itemId: String): String = newIds[itemId] ?: itemId
}

/**
 * Where a restore puts the marks of books from files. A book still here under its id keeps them. A book that came
 * back under another id (its file picked again from another folder, or after a reinstall) gets them when it is the
 * same file (size and partial MD5); for a backup that doesn't know the file, when it is the only book here with that
 * title and author (and format, when known). Anything else stays as it was. Server books are never moved.
 */
internal fun planLocalRehome(
    marks: Map<String, MarkedBookName>,
    backupBooks: List<LocalBookIdentity>,
    here: List<LocalBookIdentity>,
): LocalRehomePlan {
    val hereIds = here.mapTo(HashSet(), LocalBookIdentity::itemId)
    val known = backupBooks.associateBy(LocalBookIdentity::itemId)
    val newIds = mutableMapOf<String, String>()
    val notHere = mutableSetOf<String>()
    val unsure = mutableSetOf<String>()
    marks.forEach { (itemId, name) ->
        if (!isLocalBookId(itemId) || itemId in hereIds) return@forEach
        val book = known[itemId]
        val candidates = if (book != null && book.hasFingerprint) {
            here.filter { it.sameFileAs(book) }.sortedBy(LocalBookIdentity::itemId).take(1)
        } else {
            here.filter { it.sameNameAs(name) && it.fitsFormatOf(itemId, book?.format) }
        }
        when (candidates.size) {
            0 -> notHere += itemId
            1 -> newIds[itemId] = candidates.single().itemId
            else -> unsure += itemId
        }
    }
    return LocalRehomePlan(newIds = newIds, notHere = notHere, unsure = unsure)
}

private fun LocalBookIdentity.sameNameAs(name: MarkedBookName): Boolean =
    title.normalizedName() == name.title.normalizedName() && author.normalizedName() == name.author.normalizedName()

/** The format the backup knew, or, for an older backup, the same kind of id (a comic for a comic). */
private fun LocalBookIdentity.fitsFormatOf(itemId: String, format: String?): Boolean =
    if (format != null) this.format.equals(format, ignoreCase = true) else this.itemId.substringBefore('-') == itemId.substringBefore('-')

private fun String?.normalizedName(): String = this?.trim()?.replace(Regex("\\s+"), " ")?.lowercase(Locale.ROOT).orEmpty()
