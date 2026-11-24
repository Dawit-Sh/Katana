package com.example.unifiedreader

import android.content.Context
import android.net.Uri
import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.network.PartialDownloadFile
import com.example.unifiedreader.data.grimmory.KtorGrimmoryApi
import io.ktor.client.HttpClient
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What became of the book being read on the PC, after pairing. */
sealed interface KatanaDeskBringing {
    val title: String?

    /** Nothing is being read on the PC. */
    data object NothingRead : KatanaDeskBringing {
        override val title: String? = null
    }

    /** This device has that book already (the same file): it syncs from now on. */
    data class AlreadyHere(val itemId: String, override val title: String) : KatanaDeskBringing

    /** A Grimmory book there that is on Grimmory here too: it syncs through Grimmory. */
    data class OnGrimmory(override val title: String) : KatanaDeskBringing

    /** Downloaded from the PC into the Local library; it syncs from now on. */
    data class Arrived(val itemId: String, override val title: String) : KatanaDeskBringing

    /** A kind of book this device doesn't sync with the PC (MOBI, AZW3, FB2, DjVu). */
    data class NotSynced(override val title: String, val format: String) : KatanaDeskBringing

    /** It didn't come ([problem] says why); [triesAgain]: the next sync tries again (a download goes on where it stopped). */
    data class Failed(override val title: String?, val problem: String, val triesAgain: Boolean) : KatanaDeskBringing
}

/** How many syncs try to bring the PC's book before giving up (a lost connection doesn't count). */
internal const val KATANA_DESK_BRING_TRIES = 3

/** The kinds of book that sync with Katana Desk. */
internal val KATANA_DESK_FORMATS = setOf(FormatType.EPUB, FormatType.PDF, FormatType.CBZ, FormatType.CBR)

/** Where books downloaded from Katana Desk live, in the app's own space (removing the book removes its file). */
internal const val KATANA_DESK_BOOKS_DIR = "katana-desk-books"

/** A book from Katana Desk while it downloads (a broken-off download goes on from here). */
internal const val KATANA_DESK_PARTIAL_DIR = "katana-desk-downloads"

/**
 * Brings the book being read on the PC to this device after pairing: nothing to do when it's here already (the same
 * file: then it's linked), or on Grimmory here too; otherwise its file is downloaded from the desk (Grimmory's own
 * resumable download, which the desk answers) into the Local library and linked, so its place, highlights, bookmarks
 * and status sync from the first sync on.
 */
internal class KatanaDeskBookBringer(
    private val context: Context,
    private val httpClient: HttpClient,
    private val documents: LocalDocumentDataSource,
    private val fingerprints: LocalFileFingerprinter,
    /** Whether this device's Grimmory library has the book with that Grimmory id */
    private val onGrimmoryHere: suspend (String) -> Boolean,
    /** Whether a book here syncs with Grimmory (then not with the desk) */
    private val syncsWithGrimmory: (ReadableItem) -> Boolean,
) {
    suspend fun bring(
        desk: KatanaDeskPaired,
        reading: KatanaDeskReading,
        links: Map<String, String>,
        link: (itemId: String, deskBookId: String) -> Unit,
    ): KatanaDeskBringing {
        val items = documents.getImportedItems()
        val linkedHere = links.entries.firstOrNull { (itemId, bookId) -> bookId == reading.bookId && items.any { it.id == itemId } }
        val format = reading.format?.takeIf { it in KATANA_DESK_FORMATS }
        val sameFile = format?.takeIf { linkedHere == null }?.let { kind -> sameFileHere(reading, items.filter { it.format == kind }) }
        return when {
            linkedHere != null -> KatanaDeskBringing.AlreadyHere(linkedHere.key, reading.title)
            format == null -> KatanaDeskBringing.NotSynced(reading.title, reading.formatName.uppercase())
            sameFile != null -> {
                linkUnlessGrimmory(sameFile, reading, link)
                KatanaDeskBringing.AlreadyHere(sameFile.id, sameFile.title)
            }
            reading.grimmoryId != null && onGrimmoryHere(reading.grimmoryId) -> KatanaDeskBringing.OnGrimmory(reading.title)
            else -> downloadAndImport(desk, reading, format, link)
        }
    }

    private fun linkUnlessGrimmory(item: ReadableItem, reading: KatanaDeskReading, link: (String, String) -> Unit) {
        if (!syncsWithGrimmory(item)) link(item.id, reading.bookId)
    }

    // The book's file from the desk, into the Local library, linked to the desk's book.
    private suspend fun downloadAndImport(
        desk: KatanaDeskPaired,
        reading: KatanaDeskReading,
        format: FormatType,
        link: (String, String) -> Unit,
    ): KatanaDeskBringing {
        val file = download(desk, reading, format)
        val item = documents.import(Uri.fromFile(file))
        if (item == null) file.delete()
        val imported = item?.let { documents.getDocumentUri(it) }?.toString()?.let(::fileOfUri)
        return when {
            item == null -> throw IOException("Katana couldn't open ${reading.title} from the PC.")
            imported == null || imported.canonicalFile != file.canonicalFile -> {
                // the same file was here under another name after all: that one stays, this copy goes
                file.delete()
                linkUnlessGrimmory(item, reading, link)
                KatanaDeskBringing.AlreadyHere(item.id, item.title)
            }
            else -> KatanaDeskBringing.Arrived(item.id, item.title).also { link(item.id, reading.bookId) }
        }
    }

    // The book here whose file is the desk's (the same size and partial MD5).
    private suspend fun sameFileHere(reading: KatanaDeskReading, candidates: List<ReadableItem>): ReadableItem? {
        val md5 = reading.partialMd5
        val size = reading.sizeBytes
        if (md5 == null || size == null) return null
        return candidates.firstOrNull { item ->
            val file = documents.getDocumentUri(item)?.toString()?.let { fingerprints.of(it) }
            file != null && file.sizeBytes == size && file.partialMd5.equals(md5, ignoreCase = true)
        }
    }

    private suspend fun download(desk: KatanaDeskPaired, reading: KatanaDeskReading, format: FormatType): File {
        // In the app's own files, not its cache: a file moved out of the cache keeps the cache's group, and Android
        // would count the book as cache.
        val partial = PartialDownloadFile(
            File(File(context.filesDir, KATANA_DESK_PARTIAL_DIR), "${desk.deskId.fileSafe()}-${reading.bookId.fileSafe()}.part"),
        )
        // Resumes a download a lost connection broke off (the desk answers ranges for the same file).
        KtorGrimmoryApi(httpClient = httpClient, baseUrl = desk.baseUrl)
            .downloadBookContentResumably(accessToken = desk.token, bookId = reading.bookId, target = partial, bookType = null)
        return withContext(Dispatchers.IO) {
            val got = RandomAccessFile(partial.dataFile, "r").use { fingerprintOf(it.channel) }
            val sizeOk = reading.sizeBytes == null || got.sizeBytes == reading.sizeBytes
            val md5Ok = reading.partialMd5 == null || got.partialMd5.equals(reading.partialMd5, ignoreCase = true)
            if (!sizeOk || !md5Ok) {
                partial.delete()
                throw IOException("${reading.title} came from the PC damaged. It will be tried again.")
            }
            val folder = File(context.filesDir, KATANA_DESK_BOOKS_DIR).apply { mkdirs() }
            val target = uniqueFileIn(folder, safeBookFileName(reading.fileName, reading.title, format))
            if (!partial.dataFile.renameTo(target)) {
                partial.dataFile.copyTo(target)
                partial.dataFile.delete()
            }
            partial.infoFile.delete()
            target
        }
    }
}

/** A file name for a book from the PC: its own name made safe for any file system, with the format's extension. */
internal fun safeBookFileName(fileName: String?, title: String, format: FormatType): String {
    val extension = format.name.lowercase()
    val wanted = fileName?.takeIf { it.isNotBlank() } ?: "$title.$extension"
    val cleaned = wanted.map { char -> if (char < ' ' || char in UNSAFE_FILE_CHARS) '_' else char }.joinToString("").trim(' ', '.')
    val stem = cleaned.removeSuffixIgnoreCase(".$extension").trim(' ', '.').take(MAX_FILE_STEM).trimEnd(' ', '.')
    return "${stem.ifEmpty { "book" }}.$extension"
}

/** [name] in [folder], or "name (2).ext" and on when a file has it already. */
internal fun uniqueFileIn(folder: File, name: String): File {
    val first = File(folder, name)
    if (!first.exists()) return first
    val stem = name.substringBeforeLast('.')
    val extension = name.substringAfterLast('.', missingDelimiterValue = "")
    return generateSequence(2) { it + 1 }
        .map { n -> File(folder, if (extension.isEmpty()) "$stem ($n)" else "$stem ($n).$extension") }
        .first { !it.exists() }
}

/** The file of a book downloaded from Katana Desk at [uri] (only in the app's own folder for them), else null. */
internal fun katanaDeskDownloadAt(filesDir: File, uri: String): File? {
    val file = fileOfUri(uri) ?: return null
    val folder = File(filesDir, KATANA_DESK_BOOKS_DIR).canonicalFile
    return file.takeIf { runCatching { it.canonicalFile.parentFile == folder }.getOrDefault(false) }
}

/** The file a `file:` address names (its letters decoded), else null. */
internal fun fileOfUri(uri: String): File? =
    runCatching { java.net.URI(uri).takeIf { it.scheme == "file" }?.let(::File) }.getOrNull()

private fun String.fileSafe(): String = map { if (it.isLetterOrDigit() || it == '-') it else '_' }.joinToString("").take(MAX_ID_CHARS)

private const val UNSAFE_FILE_CHARS = "\\/:*?\"<>|"
private const val MAX_FILE_STEM = 120
private const val MAX_ID_CHARS = 40
